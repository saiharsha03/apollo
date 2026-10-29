"""Lemnos for the phone: the VPS's health, its files, a terminal, the remote desktop and service restarts. Same device
token as every phone route; the phone asks for his fingerprint before a terminal, a delete or a restart.

Files: two roots, the workspace and the mini apps, never the data dir itself (it holds the token hashes and the keys the
phone handed over). Writes stay inside those roots and never touch an app's data.db.
Terminal: `tmux attach` in a pseudo-terminal, bridged to a WebSocket (Zeus's term.py). The server reaches agents's tmux
and systemd only through deploy/apollo-lemnos over sudo; Iris's session (claude-auto) is reachable; only Apollo's own shells can be closed.
Terminal key: the terminal and shell create/kill also need a signature from the phone's fingerprint-locked Keystore key
over a one-time challenge, so the device token alone never opens a shell. The phone registers its public key as pending;
root approves it by its short code (deploy/lemnos-approve.sh), so a stolen token can't enrol its own key. An active key
is never replaced here; only root resets it (deploy/lemnos-reset.sh).
Remote desktop: Xvnc on a unix socket (apollo-desktop.service); the phone speaks RFB itself, this only copies bytes.
"""
import asyncio
import base64
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import APIRouter, HTTPException, Request, WebSocket
from fastapi.websockets import WebSocketDisconnect
from pydantic import BaseModel, Field

from . import config

TEXT_MAX = 1 << 20
FILE_MAX = 64 << 20          # Caddy's request cap
MAX_IN = 64 * 1024
# Units whose state the Server tab shows; settable per box with APOLLO_LEMNOS_UNITS (comma separated).
UNITS = [u for u in os.environ.get("APOLLO_LEMNOS_UNITS", "apollo-server.service,caddy.service,iris.service").split(",") if u]
# The ones he may start, stop and restart: root's list (the helper checks it again, with its own never-list).
CONTROL = Path(os.environ.get("APOLLO_LEMNOS_CONTROL", "/etc/apollo/lemnos.json"))
HELPER = os.environ.get("APOLLO_LEMNOS_HELPER", "/opt/apollo/bin/apollo-lemnos")
AS_AGENTS = ["/usr/bin/sudo", "-n", "-u", "agents", HELPER]
AS_ROOT = ["/usr/bin/sudo", "-n", HELPER]
PROTECTED = set()      # Iris's session (claude-auto) is his to open now (2026-10-27); only Apollo's shells can be closed
DESKTOP = "apollo-desktop.service"
DESKTOP_SOCK = os.environ.get("APOLLO_DESKTOP_SOCK", "/run/apollo-desktop/vnc.sock")
KEY = "lemnos_key"           # setting: the phone's terminal public key (base64 DER), active once root approved it
PENDING = "lemnos_key_pending"     # a registered key waiting for root's approval; never opens anything
CHALLENGE_TTL = 60
_challenges: dict[str, float] = {}     # nonce -> expiry; each one is good for a single signature
_tickets: dict[str, tuple[str, float]] = {}     # a signed new shell's one-time pass to its own terminal (one prompt, not two)


def roots() -> dict[str, Path]:
    return {"workspace": config.WORKSPACE, "apps": config.APPS}


def _parts_ok(path: str) -> bool:
    return not any(p in ("", ".", "..") or p.startswith(".") for p in path.split("/") if path)


def resolve(root: str, path: str) -> Path:
    """The file or folder under a root, or 404. No .., no hidden names, nothing a symlink takes outside the root."""
    base = roots().get(root)
    if base is None or not _parts_ok(path):
        raise HTTPException(404)
    base = base.resolve()
    target = (base / path).resolve() if path else base
    if target != base and base not in target.parents:
        raise HTTPException(404)
    if not target.exists():
        raise HTTPException(404)
    return target


def _writable_name(name: str) -> str:
    """A name a write may create or change: plain, not hidden, never an app's database."""
    if not name or "/" in name or "\\" in name or "\0" in name or name.startswith(".") or name.lower().startswith("data.db") or len(name) > 200:
        raise HTTPException(400, "not an allowed name")
    return name


def resolve_new(root: str, path: str) -> Path:
    """Where a new file goes: an existing folder under the root, plus an allowed name that isn't taken."""
    parent, _, name = path.rpartition("/")
    folder = resolve(root, parent)
    if not folder.is_dir():
        raise HTTPException(400, "not a folder")
    target = folder / _writable_name(name)
    if target.exists() or target.is_symlink():
        raise HTTPException(409, "already there")
    return target


def resolve_own(root: str, path: str) -> Path:
    """An existing file or folder a write may change: not the root itself, not data.db."""
    if not path:
        raise HTTPException(400, "not the root")
    target = resolve(root, path)
    _writable_name(target.name)
    return target


def write(f: Path, body: bytes, replace: bool) -> None:
    """Every Lemnos write: a fresh temp file in the same folder (mkstemp: O_EXCL, 0600, so never through a planted link),
    then moved in. An edit goes over the file only while it is still a plain file; an upload goes only where nothing
    (not even a link) is, by a hard link that fails if a name appeared meanwhile (scan F18)."""
    fd, tmp = tempfile.mkstemp(dir=f.parent, prefix=".lemnos-")
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(body)
        if replace:
            if f.is_symlink() or not f.is_file():
                raise HTTPException(409, "it changed while saving")
            os.chmod(tmp, f.stat().st_mode & 0o777)
            os.replace(tmp, f)
        else:
            os.link(tmp, f)
    except FileExistsError:
        raise HTTPException(409, "already there")
    finally:
        if os.path.lexists(tmp):
            os.unlink(tmp)


def _meminfo() -> dict[str, int]:
    out = {}
    try:
        for line in open("/proc/meminfo"):
            k, v = line.split(":", 1)
            out[k] = int(v.split()[0]) * 1024
    except OSError:
        pass
    return out


def _unit(name: str) -> str:
    try:
        r = subprocess.run(["systemctl", "is-active", name], capture_output=True, text=True, timeout=3)
        return r.stdout.strip() or "unknown"
    except (OSError, subprocess.TimeoutExpired):
        return "unknown"


def controls() -> list[str]:
    try:
        units = json.loads(CONTROL.read_text()).get("units", [])
    except (OSError, ValueError, AttributeError):
        return []
    return [u for u in units if isinstance(u, str)]


def health() -> dict:
    m = _meminfo()
    disk = shutil.disk_usage(config.DATA if config.DATA.exists() else "/")
    try:
        up = int(float(open("/proc/uptime").read().split()[0]))
    except OSError:
        up = 0
    try:
        load = list(os.getloadavg())
    except (OSError, AttributeError):     # AttributeError: not on Linux (tests on Windows)
        load = []
    ctl = controls()
    return {"load": load, "mem_total": m.get("MemTotal", 0), "mem_used": m.get("MemTotal", 0) - m.get("MemAvailable", 0),
            "disk_total": disk.total, "disk_used": disk.used, "uptime": up,
            "units": {u: _unit(u) for u in dict.fromkeys(UNITS + [u for u in ctl if u != DESKTOP])}, "control": [u for u in ctl if u != DESKTOP],
            "at": time.time()}


# ---------------------------------------------------------------- the terminal key
def challenge() -> str:
    now = time.monotonic()
    for n, end in list(_challenges.items()):
        if end < now:
            _challenges.pop(n, None)
    if len(_challenges) >= 50:
        raise HTTPException(429, "too many challenges")
    n = secrets.token_urlsafe(24)
    _challenges[n] = now + CHALLENGE_TTL
    return n


def _b64(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def key_id(der_b64: str | None) -> str | None:
    return hashlib.sha256(_b64(der_b64)).hexdigest() if der_b64 else None


def code(der_b64: str | None) -> str | None:
    """The code the phone shows and root types to approve a pending key: 64 bits, so nobody can grind a key to match it."""
    return key_id(der_b64)[:16] if der_b64 else None


def approve(store, typed: str) -> bool:
    """Root's approval: the pending key becomes the active one, only when the code matches and none is active yet."""
    with store.tx():
        pending = store.setting(PENDING)
        if store.setting(KEY) or not pending or code(pending) != typed.strip().lower():
            return False
        store.set_setting(KEY, pending)
        store.x("DELETE FROM settings WHERE name = ?", (PENDING,))
    store.log("root", "Lemnos terminal key approved")
    return True


def signed(der_b64: str | None, nonce: str, sig: str, what: str) -> bool:
    """The phone's key signed "<nonce>:<what>" over a live challenge. The challenge is spent either way."""
    end = _challenges.pop(nonce or "", None)
    if end is None or end < time.monotonic() or not der_b64:
        return False
    try:
        pub = serialization.load_der_public_key(_b64(der_b64))
        pub.verify(_b64(sig or ""), f"{nonce}:{what}".encode(), ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError, TypeError):
        return False


def ticket(session: str) -> str:
    now = time.monotonic()
    for t, (_, end) in list(_tickets.items()):
        if end < now:
            _tickets.pop(t, None)
    t = secrets.token_urlsafe(24)
    _tickets[t] = (session, now + CHALLENGE_TTL)
    return t


def ticket_ok(t: str, session: str) -> bool:
    got = _tickets.pop(t or "", None)
    return got is not None and got[0] == session and got[1] >= time.monotonic()


class KeyIn(BaseModel):
    key: str
    nonce: str
    sig: str


# ---------------------------------------------------------------- the helper
def helper(prefix: list[str], *args: str, timeout: float = 20) -> bytes:
    """One sudo call to apollo-lemnos; its {"code","error"} becomes an HTTP error."""
    try:
        r = subprocess.run(prefix + list(args), capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "helper timed out")
    except OSError:
        raise HTTPException(503, "helper not installed")
    if r.returncode != 0:
        try:
            e = json.loads(r.stderr)
            raise HTTPException(int(e.get("code", 502)), str(e.get("error", "helper failed")))
        except (ValueError, AttributeError):
            raise HTTPException(502, (r.stderr.decode(errors="replace").strip() or f"helper exit {r.returncode}")[:200])
    return r.stdout


def session_ok(name: str) -> str:
    if not name or name.lower() in PROTECTED:
        raise HTTPException(403, "that session is off limits")
    return name


def _num(v, lo, hi, default):
    try:
        return max(lo, min(hi, int(v)))
    except (TypeError, ValueError):
        return default


async def term_bridge(ws: WebSocket, session: str, read_only: bool, cols, rows) -> None:
    """Zeus's terminal: the session's earlier lines first as {"t":"history","d":...}, then raw bytes. The phone sends
    {"t":"in","d":...}, {"t":"resize","cols","rows"} and {"t":"scroll","dir":"up"|"down"|"end"}. Closing detaches;
    tmux keeps going."""
    import fcntl
    import struct
    import termios

    def winsize(fd, c, r):
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", r, c, 0, 0))

    cols, rows = _num(cols, 20, 400, 80), _num(rows, 5, 200, 24)
    try:
        hist = (await asyncio.to_thread(helper, AS_AGENTS, "capture", session, "2000")).decode(errors="replace")
        await ws.send_text(json.dumps({"t": "history", "d": hist.rstrip("\n").replace("\n", "\r\n") + "\r\n"}))
    except HTTPException:
        pass
    master, slave = os.openpty()
    winsize(master, cols, rows)
    # setsid -c: a new session whose controlling terminal is the pty, so resizes reach sudo and tmux
    proc = await asyncio.create_subprocess_exec("/usr/bin/setsid", "-c", *AS_AGENTS, "attach", session, "ro" if read_only else "rw",
                                                stdin=slave, stdout=slave, stderr=slave,
                                                env={"PATH": "/usr/bin:/bin", "TERM": "xterm-256color", "LANG": "C.UTF-8"})
    os.close(slave)
    os.set_blocking(master, False)
    loop = asyncio.get_running_loop()
    q: asyncio.Queue = asyncio.Queue(maxsize=512)

    def readable():
        try:
            data = os.read(master, 65536)
        except BlockingIOError:
            return
        except OSError:
            data = b""
        if not data:
            loop.remove_reader(master)
        try:
            q.put_nowait(data or None)
        except asyncio.QueueFull:
            pass     # a slow phone loses a frame; tmux's next redraw catches up

    loop.add_reader(master, readable)

    async def to_phone():
        while (data := await q.get()) is not None:
            await ws.send_bytes(data)

    async def from_phone():
        while True:
            raw = await ws.receive_text()
            if len(raw) > MAX_IN:
                continue
            try:
                m = json.loads(raw)
            except ValueError:
                continue
            if m.get("t") == "in" and not read_only:
                data = str(m.get("d", "")).encode()
                while data:
                    try:
                        data = data[os.write(master, data):]
                    except BlockingIOError:
                        await asyncio.sleep(0.01)
            elif m.get("t") == "scroll" and m.get("dir") in ("up", "down", "end"):
                try:
                    await asyncio.to_thread(helper, AS_AGENTS, "scroll", session, m["dir"])
                except HTTPException:
                    pass
            elif m.get("t") == "resize":
                winsize(master, _num(m.get("cols"), 20, 400, cols), _num(m.get("rows"), 5, 200, rows))

    tasks = [asyncio.create_task(to_phone()), asyncio.create_task(from_phone())]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        try:
            loop.remove_reader(master)
        except (ValueError, OSError):
            pass
        os.close(master)     # hangs up the terminal: sudo and the tmux client exit; the session keeps running
        if proc.returncode is None:
            try:
                await asyncio.wait_for(proc.wait(), 5)
            except asyncio.TimeoutError:
                try:
                    proc.kill()
                except (ProcessLookupError, PermissionError):
                    pass
        try:
            await asyncio.to_thread(helper, AS_AGENTS, "release", session)
        except HTTPException:
            pass
        try:
            await ws.close()
        except RuntimeError:
            pass


# ---------------------------------------------------------------- the desktop
def desktop_status() -> dict:
    try:
        r = subprocess.run(["systemctl", "show", DESKTOP, "--no-pager", "-p", "ActiveState,MemoryCurrent"], capture_output=True, text=True, timeout=5)
        st = dict(ln.split("=", 1) for ln in r.stdout.splitlines() if "=" in ln)
    except (OSError, subprocess.TimeoutExpired):
        st = {}
    state = st.get("ActiveState", "unknown")
    mem = st.get("MemoryCurrent", "")
    return {"state": state, "ready": state == "active" and os.path.exists(DESKTOP_SOCK), "memory": int(mem) if mem.isdigit() else None}


async def desktop_bridge(ws: WebSocket) -> None:
    """Bytes both ways, unchanged. The phone's RFB client does the protocol."""
    try:
        reader, writer = await asyncio.open_unix_connection(DESKTOP_SOCK)
    except (OSError, AttributeError):
        await ws.close(code=4404)
        return
    await ws.accept()

    async def to_phone():
        while data := await reader.read(256 * 1024):
            await ws.send_bytes(data)

    async def from_phone():
        while True:
            m = await ws.receive()
            if m["type"] == "websocket.disconnect":
                return
            if m.get("bytes"):
                writer.write(m["bytes"])
                await writer.drain()

    tasks = [asyncio.create_task(to_phone()), asyncio.create_task(from_phone())]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        writer.close()
        try:
            await ws.close()
        except (RuntimeError, WebSocketDisconnect):
            pass


class Act(BaseModel):
    action: str = Field(pattern=r"^(start|stop|restart)$")
    unit: str = ""


class Change(BaseModel):
    root: str
    path: str
    to: str = ""


def router(phone, store) -> APIRouter:
    r = APIRouter()

    def proved(q, what: str) -> bool:
        return signed(store.setting(KEY), q.get("nonce", ""), q.get("sig", ""), what)

    @r.get("/v1/lemnos/challenge")
    def get_challenge(req: Request):
        phone(req)
        return {"nonce": challenge(), "key": key_id(store.setting(KEY)), "pending": key_id(store.setting(PENDING))}

    @r.post("/v1/lemnos/key")
    def register(req: Request, b: KeyIn):
        """The phone's terminal key, as pending until root approves it. Neither a pending nor an active key is ever replaced
        here (root resets them on the server), so a stolen token can't swap its own key in before he approves."""
        phone(req)
        try:
            pub = serialization.load_der_public_key(_b64(b.key))
        except ValueError:
            raise HTTPException(400, "not a public key")
        if not isinstance(pub, ec.EllipticCurvePublicKey) or pub.curve.name != "secp256r1":
            raise HTTPException(400, "not a P-256 key")
        with store.tx():
            if store.setting(KEY) or store.setting(PENDING) not in (None, b.key):
                _challenges.pop(b.nonce, None)
                raise HTTPException(409, "a terminal key is already set; reset it on the server as root")
            if not signed(b.key, b.nonce, b.sig, "register"):
                raise HTTPException(403, "bad signature")
            store.set_setting(PENDING, b.key)
        store.log("apollo", f"Lemnos terminal key {code(b.key)} registered, waiting for root's approval")
        return {"pending": code(b.key)}

    def phone_ws(ws: WebSocket) -> bool:
        try:
            phone(ws)        # reads only the Authorization header, which a WebSocket has too
            return True
        except HTTPException:
            return False

    @r.get("/v1/lemnos/status")
    def status(req: Request):
        phone(req)
        return health()

    @r.get("/v1/lemnos/files")
    def files(req: Request, root: str = "", path: str = ""):
        phone(req)
        if not root:
            return {"roots": list(roots())}
        d = resolve(root, path)
        if not d.is_dir():
            raise HTTPException(400, "not a folder")
        out = []
        for e in sorted(d.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower())):
            if e.name.startswith("."):
                continue
            st = e.stat()
            out.append({"name": e.name, "dir": e.is_dir(), "size": st.st_size, "modified": int(st.st_mtime * 1000)})
        return {"entries": out}

    @r.get("/v1/lemnos/text")
    def text(req: Request, root: str, path: str):
        phone(req)
        f = resolve(root, path)
        if not f.is_file() or f.stat().st_size > TEXT_MAX or f.name == "data.db":
            raise HTTPException(415, "not a text file")
        try:
            return {"text": f.read_bytes().decode("utf-8")}
        except UnicodeDecodeError:
            raise HTTPException(415, "not a text file")

    @r.put("/v1/lemnos/file")
    async def put_file(req: Request, root: str, path: str, replace: bool = False):
        """Upload a new file (never over an old one), or with replace=1 save an edited text file over itself."""
        phone(req)
        body = await req.body()
        if len(body) > FILE_MAX:
            raise HTTPException(413, "file too large")
        if replace:
            f = resolve_own(root, path)
            if not f.is_file() or len(body) > TEXT_MAX:
                raise HTTPException(415, "not a text file")
            try:
                body.decode("utf-8")
            except UnicodeDecodeError:
                raise HTTPException(415, "not a text file")
        else:
            f = resolve_new(root, path)
        write(f, body, replace)
        return {"ok": True}

    @r.post("/v1/lemnos/rename")
    def rename(req: Request, b: Change):
        phone(req)
        f = resolve_own(b.root, b.path)
        to = f.parent / _writable_name(b.to)
        if to.exists() or to.is_symlink():
            raise HTTPException(409, "already there")
        try:
            if f.is_file() and not f.is_symlink():
                os.link(f, to)       # never over a name that appeared since the check (rename would replace it)
                f.unlink()
            else:
                f.rename(to)
        except FileExistsError:
            raise HTTPException(409, "already there")
        return {"ok": True}

    @r.post("/v1/lemnos/folder")
    def folder(req: Request, b: Change):
        """A new empty folder: the same roots and names as an upload, never over anything."""
        phone(req)
        f = resolve_new(b.root, b.path)
        try:
            f.mkdir()
        except FileExistsError:
            raise HTTPException(409, "already there")
        return {"ok": True}

    @r.post("/v1/lemnos/delete")
    def delete(req: Request, b: Change):
        phone(req)
        f = resolve_own(b.root, b.path)
        if f.is_dir():
            if next(f.rglob("data.db*"), None):
                raise HTTPException(403, "that folder holds an app's data")
            shutil.rmtree(f)
        else:
            f.unlink()
        return {"ok": True}

    @r.get("/v1/lemnos/sessions")
    def sessions(req: Request):
        phone(req)
        rows = json.loads(helper(AS_AGENTS, "sessions") or b"[]")
        return {"sessions": [s for s in rows if str(s.get("name", "")).lower() not in PROTECTED]}

    @r.post("/v1/lemnos/sessions")
    def new_shell(req: Request):
        phone(req)
        if not proved(req.query_params, "new-shell"):
            raise HTTPException(403, "fingerprint needed")
        out = json.loads(helper(AS_AGENTS, "new-shell"))
        return {**out, "ticket": ticket(str(out.get("session", "")))}

    @r.delete("/v1/lemnos/sessions/{name}")
    def kill_shell(req: Request, name: str):
        phone(req)
        session_ok(name)
        if not proved(req.query_params, f"kill:{name}"):
            raise HTTPException(403, "fingerprint needed")
        return json.loads(helper(AS_AGENTS, "kill-shell", session_ok(name)))

    @r.websocket("/v1/lemnos/term")
    async def term(ws: WebSocket, session: str = "", ro: bool = False, cols: int = 80, rows: int = 24):
        # His fingerprint each time: a signed challenge, or the ticket a signed new shell handed back
        signed_in = lambda: ticket_ok(ws.query_params.get("ticket", ""), session) or proved(ws.query_params, f"term:{session}")  # noqa: E731
        if not phone_ws(ws) or not session or session.lower() in PROTECTED or not signed_in():
            await ws.close(code=4403)
            return
        await ws.accept()
        await term_bridge(ws, session, ro, cols, rows)

    @r.post("/v1/lemnos/unit")
    def unit(req: Request, b: Act):
        phone(req)
        if b.unit not in controls() or b.unit == DESKTOP:
            raise HTTPException(403, "that service isn't on the list")
        helper(AS_ROOT, "unit", b.action, b.unit, timeout=100)
        return {"unit": b.unit, "state": _unit(b.unit)}

    @r.get("/v1/lemnos/desktop")
    def desktop(req: Request):
        phone(req)
        return desktop_status()

    @r.post("/v1/lemnos/desktop")
    def desktop_act(req: Request, b: Act):
        phone(req)
        if b.action == "restart":
            raise HTTPException(400, "start or stop")
        helper(AS_ROOT, "unit", b.action, DESKTOP, timeout=100)
        if b.action == "start":
            end = time.monotonic() + 20
            while not os.path.exists(DESKTOP_SOCK) and time.monotonic() < end:
                time.sleep(0.3)
        return desktop_status()

    @r.websocket("/v1/lemnos/desktop/view")
    async def desktop_view(ws: WebSocket):
        if not phone_ws(ws):
            await ws.close(code=4401)
            return
        await desktop_bridge(ws)

    return r


if __name__ == "__main__":      # deploy/lemnos-approve.sh, as apollo: python -m apollo.lemnos approve <code>
    import sys
    from .store import Store
    if len(sys.argv) != 3 or sys.argv[1] != "approve":
        sys.exit("usage: python -m apollo.lemnos approve <code>")
    ok = approve(Store(), sys.argv[2])
    print("Approved: the phone's terminal key is active." if ok else "Not approved: no pending key with that code (or a key is already active).")
    sys.exit(0 if ok else 1)
