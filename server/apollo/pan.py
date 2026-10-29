"""Pan, the Plazmi resident (Zeus's gateway/plazmi.py, docs/PLAZMI.md there): it stays in its own container on the VPS.
Apollo reads its feed file (untrusted: fixed fields, control characters stripped, capped), copies new items into the
plazmi mini app's `feed` table every few minutes, and writes the only things that flow in: what Owner or Athena says to
it (inbox.jsonl), his daily token limit and pause (limits.json), his move-in name. Claiming it and setting its Anthropic
key need root (the broker's private dir, /etc, a container restart): the server asks deploy/apollo-pan-root through
sudo, which does exactly those three things. The phone asks for his fingerprint before any of them.
"""
import json
import os
import re
import stat
import subprocess
import tempfile
import threading
import time
from pathlib import Path

from . import apps

FEED = Path(os.environ.get("APOLLO_PLAZMI_FEED", "/var/lib/plazmi/state/feed.jsonl"))
LIMITS = Path(os.environ.get("APOLLO_PLAZMI_LIMITS", "/var/lib/plazmi/limits/limits.json"))
INBOX = Path(os.environ.get("APOLLO_PLAZMI_INBOX", "/var/lib/plazmi/limits/inbox.jsonl"))
MOVEIN = Path(os.environ.get("APOLLO_PLAZMI_MOVEIN", "/var/lib/plazmi/limits/movein.json"))
UID = int(os.environ.get("APOLLO_PLAZMI_UID", "10001")) if os.name == "posix" else None   # only the resident's own file
ROOT_HELPER = os.environ.get("APOLLO_PAN_ROOT", "/opt/apollo/bin/apollo-pan-root")
DAILY_DEFAULT = 5_000_000
DAILY_MAX = 1_000_000_000

TAIL = 256 * 1024
TEXT, PLACE, SAY = 500, 120, 1000
KEEP = 300                 # feed rows kept in the mini app
INBOX_KEEP = 50
KINDS = {"diary", "postcard", "status", "event", "reply"}
_RANGES = [(0x00, 0x08), (0x0B, 0x1F), (0x7F, 0x9F), (0x200B, 0x200F), (0x202A, 0x202E), (0x2066, 0x2069)]
_CTRL = re.compile("[" + "".join(re.escape(chr(a)) + "-" + re.escape(chr(b)) for a, b in _RANGES) + "]")
_lock = threading.Lock()


def clean(s, cap: int) -> str:
    s = _CTRL.sub("", str(s)).replace("\n", " ").replace("\t", " ")
    return re.sub(r" {2,}", " ", s).strip()[:cap]


def item(o):
    if not isinstance(o, dict) or o.get("kind") not in KINDS:
        return None
    ts = o.get("ts")
    if isinstance(ts, bool) or not isinstance(ts, int) or not 0 < ts < 10**14:
        return None
    text = clean(o["text"], TEXT) if isinstance(o.get("text"), str) else ""
    if not text:
        return None
    out = {"ts": ts, "kind": o["kind"], "text": text, "place": None, "used": None, "token_limit": None}
    if isinstance(o.get("place"), str):
        out["place"] = clean(o["place"], PLACE) or None
    for k, col in (("used", "used"), ("limit", "token_limit")):
        v = o.get(k)
        if o["kind"] == "status" and isinstance(v, int) and not isinstance(v, bool) and 0 <= v < 10**12:
            out[col] = v
    return out


def _tail(path) -> bytes | None:
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    except OSError:
        return None
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or (UID is not None and st.st_uid != UID):
            return None
        start = max(0, st.st_size - TAIL)
        os.lseek(fd, start, os.SEEK_SET)
        raw = b""
        while len(raw) < TAIL and (chunk := os.read(fd, 65536)):
            raw += chunk
        raw = raw[:TAIL]
        return raw.split(b"\n", 1)[1] if start and b"\n" in raw else (b"" if start else raw)
    finally:
        os.close(fd)


def feed(after=0, path=None) -> list[dict]:
    raw = _tail(path or FEED)
    out = []
    for line in (raw or b"").split(b"\n"):
        if not line.strip() or len(line) > 4096:
            continue
        try:
            it = item(json.loads(line.decode("utf-8", "replace")))
        except (ValueError, TypeError, RecursionError):
            continue
        if it and it["ts"] > after:
            out.append(it)
    return sorted(out, key=lambda x: x["ts"])


def copy_feed(path=None) -> int:
    """New feed items into plazmi's `feed` table (INSERT OR IGNORE), the newest KEEP kept. Returns how many were new."""
    try:
        last = apps.sql("plazmi", "SELECT COALESCE(MAX(ts), 0) AS ts FROM feed")[0]["ts"]
    except apps.AppError:
        return 0        # archived, or not planted yet
    new = feed(last, path)
    for it in new:
        apps.sql("plazmi", "INSERT OR IGNORE INTO feed(ts, kind, text, place, used, token_limit) VALUES(?, ?, ?, ?, ?, ?)",
                 (it["ts"], it["kind"], it["text"], it["place"], it["used"], it["token_limit"]), phone=True)
    if new:
        apps.sql("plazmi", f"DELETE FROM feed WHERE id NOT IN (SELECT id FROM feed ORDER BY ts DESC LIMIT {KEEP})", phone=True)
    return len(new)


def recent(n=200) -> list[dict]:
    """What Pan wrote, newest first, for the phone's Pan screen: the plazmi feed table (copied fresh first), else the file."""
    try:
        copy_feed()
        return apps.sql("plazmi", f"SELECT ts, kind, text, place FROM feed ORDER BY ts DESC LIMIT {int(n)}")
    except (apps.AppError, OSError):
        return [{k: it[k] for k in ("ts", "kind", "text", "place")} for it in reversed(feed())][:n]


def _replace(path: Path, text: str):
    """Atomically, 0644: the container's uid reads it through its read-only mount."""
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=path.name + ".")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(text)
    os.chmod(tmp, 0o644)
    os.replace(tmp, path)


def said(path=None) -> list[dict]:
    try:
        lines = (path or INBOX).read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    out = []
    for line in lines[-INBOX_KEEP:]:
        try:
            o = json.loads(line)
        except ValueError:
            continue
        if isinstance(o, dict) and o.get("from") in ("Owner", "Athena") and isinstance(o.get("at"), int) and isinstance(o.get("text"), str):
            out.append({"at": o["at"], "from": o["from"], "text": clean(o["text"], SAY)})
    return out


def say(text: str, who: str, path=None) -> dict:
    if who not in ("Owner", "Athena"):
        raise ValueError("only Owner or Athena talk to Pan")
    text = clean(text, SAY)
    if not text:
        raise ValueError("empty")
    path = path or INBOX
    with _lock:
        now = int(time.time() * 1000)
        prev = said(path)
        if prev and prev[-1]["at"] >= now:
            now = prev[-1]["at"] + 1
        msg = {"at": now, "from": who, "text": text}
        _replace(path, "".join(json.dumps(m, ensure_ascii=False) + "\n" for m in (prev + [msg])[-INBOX_KEEP:]))
    return msg


def limits(path=None) -> dict:
    try:
        o = json.loads((path or LIMITS).read_text(encoding="utf-8"))
        return {"daily_tokens": int(o["daily_tokens"]), "paused": bool(o["paused"]), "updated": o.get("updated"), "set": True}
    except (OSError, ValueError, KeyError, TypeError):
        return {"daily_tokens": DAILY_DEFAULT, "paused": False, "updated": None, "set": False}


def set_limits(daily_tokens, paused, path=None) -> dict:
    if isinstance(daily_tokens, bool) or not isinstance(daily_tokens, int) or not 0 <= daily_tokens <= DAILY_MAX:
        raise ValueError("daily_tokens: a whole number up to a billion")
    out = {"daily_tokens": daily_tokens, "paused": bool(paused), "updated": int(time.time() * 1000)}
    _replace(path or LIMITS, json.dumps(out))
    return out


NAME = re.compile(r"[\w .'-]{1,24}")


def move_in(name: str, emoji: str = "", path=None, inbox=None) -> dict:
    name = clean(name, 100)
    if not NAME.fullmatch(name):
        raise ValueError("name: up to 24 letters, digits, spaces, . ' -")
    emoji = clean(emoji, 8)
    out = {"name": name, "emoji": emoji, "at": int(time.time() * 1000)}
    _replace(path or MOVEIN, json.dumps(out, ensure_ascii=False))
    say(f"Please move in to Plazmi now, as {name}" + (f" {emoji}" if emoji else "") + ".", "Owner", inbox)
    return out


PHRASE = re.compile(r"[a-z]{2,24}( [a-z]{2,24}){2}")
KEY_SHAPE = re.compile(r"sk-ant-[A-Za-z0-9_-]{20,300}")


def root(cmd: str, stdin: bytes = b"") -> dict:
    """deploy/apollo-pan-root through sudo (claim, confirm, key). Errors come back as fixed text, never the input."""
    try:
        r = subprocess.run(["sudo", "-n", ROOT_HELPER, cmd], input=stdin, capture_output=True, timeout=90)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise ValueError(f"the Pan helper didn't run ({type(e).__name__}); is deploy/pan-install.sh done?") from None
    if r.returncode != 0:
        raise ValueError((r.stderr.decode("utf-8", "replace").strip().splitlines() or ["the Pan helper failed"])[-1][:200])
    try:
        return json.loads(r.stdout or b"{}")
    except ValueError:
        return {}


def claim() -> dict:
    """The claim link the broker kept at move-in, for him to open on plazmi.gg."""
    return root("claim")


def confirm(phrase: str) -> dict:
    """His three words from plazmi.gg/claim into the broker's private dir; the broker sends the one command itself."""
    p = " ".join(str(phrase).lower().split())
    if not PHRASE.fullmatch(p):
        raise ValueError("three words, as plazmi.gg/claim shows them")
    return root("confirm", json.dumps({"phrase": p}).encode())


def set_key(key: str) -> dict:
    key = str(key).strip()
    if not KEY_SHAPE.fullmatch(key):
        raise ValueError("that doesn't look like an Anthropic key (sk-ant-...)")
    return root("key", key.encode())
