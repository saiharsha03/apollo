"""Calypso drops: sealed envelopes on their way to the phone (carried over from Zeus's gateway, calypso.py).

Iris and Atlas seal an item to Calypso's public key with apollo-calypso-drop (deploy/) and drop the file into the spool
(1733: anyone may drop, nobody else can list or read). This server never sees plaintext and holds no key that opens
them: it checks the envelope's shape, stamps who dropped it from the file's owner (the kernel's word, never the
envelope's own `from`), keeps it until the phone takes it, then forgets it. The phone publishes Calypso's public key
here once; agents read it from /run/apollo/calypso.pub.pem.
"""
import base64
import json
import os
import re
import stat
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request

from . import config

try:
    import pwd
except ImportError:     # not on the server's Linux (a dev box): every drop reads as unverified
    pwd = None

ID = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
KINDS = {"secret", "note", "file"}
SENDERS = {"iris", "atlas", "athena", "apollo", "zeus", "echo"}
MAX_CT = 2 * 1024 * 1024 + 512


def spool() -> Path:
    return Path(os.environ.get("APOLLO_CALYPSO_SPOOL", "/var/spool/apollo-calypso"))


def folder() -> Path:
    d = config.DATA / "calypso"
    d.mkdir(parents=True, exist_ok=True)
    os.chmod(d, 0o700)
    return d


def valid(env: dict) -> bool:
    try:
        if env.get("v") != 1 or not ID.match(str(env.get("id", ""))) or env.get("kind") not in KINDS:
            return False
        if not isinstance(env.get("label"), str) or len(env["label"]) > 80 or env.get("from") not in SENDERS:
            return False
        int(env["at"])
        iv, ct, wk = (base64.b64decode(env[k], validate=True) for k in ("iv", "ct", "wk"))
        return len(iv) == 12 and 18 <= len(ct) <= MAX_CT and 128 <= len(wk) <= 1024
    except (KeyError, TypeError, ValueError):
        return False


def _uid(user: str):
    try:
        return pwd.getpwnam(user).pw_uid if pwd else None
    except KeyError:
        return None


def origin(uid: int) -> str:
    """Iris and Atlas share one uid (agents), so that is all it can say."""
    if uid == _uid(os.environ.get("APOLLO_AGENTS_USER", "agents")):
        return "agents"
    if uid == _uid("apollo"):
        return "apollo"
    return "unverified"


def read_drop(path: Path, limit: int):
    """(fstat, bytes) from one descriptor: no symlinks, FIFOs or devices, so a drop can't be swapped between them."""
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            raise OSError("not a regular file")
        out = bytearray()
        while len(out) < limit and (chunk := os.read(fd, min(65536, limit - len(out)))):
            out += chunk
        return st, bytes(out)
    finally:
        os.close(fd)


def intake(store) -> int:
    """Moves valid drops from the spool into the server's own folder. Bad ones are deleted and logged by name only."""
    n = 0
    if not spool().is_dir():
        return 0
    for f in sorted(spool().glob("*.json")):
        try:
            st, raw = read_drop(f, MAX_CT * 4)
            env = json.loads(raw.decode("utf-8"))
            if valid(env):
                env["origin"] = origin(st.st_uid)
                tmp = folder() / f".{env['id']}"
                tmp.write_text(json.dumps(env), encoding="utf-8")
                os.replace(tmp, folder() / f"{env['id']}.json")
                store.log("calypso", f"drop from {env['origin']} (says {env['from']}): {env['kind']}")
                n += 1
            else:
                store.log("calypso", f"bad drop {f.name[:40]}")
        except (OSError, ValueError) as e:
            store.log("calypso", f"bad drop {f.name[:40]}: {type(e).__name__}")
        f.unlink(missing_ok=True)
    return n


def drops(limit: int = 50) -> list[dict]:
    out = []
    for f in sorted(folder().glob("*.json"))[:limit]:
        try:
            out.append(json.loads(f.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            f.unlink(missing_ok=True)
    return out


def ack(ids) -> int:
    n = 0
    for i in list(ids)[:200]:
        if ID.match(str(i)) and (folder() / f"{i}.json").exists():
            (folder() / f"{i}.json").unlink()
            n += 1
    return n


def publish(pem: str) -> None:
    """Calypso's public key, for agents to seal to: kept in the data dir, copied to /run/apollo (0644) for them."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    key = serialization.load_pem_public_key(pem.encode())
    if not isinstance(key, rsa.RSAPublicKey) or key.key_size < 2048:
        raise ValueError("expected an RSA public key, 2048 bits or more")
    (config.DATA / "calypso.pub.pem").write_text(pem, encoding="utf-8")
    republish()


def republish() -> None:
    """At startup too: /run is emptied on reboot."""
    src = config.DATA / "calypso.pub.pem"
    if not src.exists():
        return
    config.RUN.mkdir(parents=True, exist_ok=True)
    out = config.RUN / "calypso.pub.pem"
    tmp = out.with_suffix(".tmp")
    tmp.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")
    os.chmod(tmp, 0o644)
    os.replace(tmp, out)


def router(phone, store) -> APIRouter:
    r = APIRouter()

    @r.get("/v1/calypso/drops")
    def get_drops(req: Request):
        phone(req)
        intake(store)
        return {"drops": drops()}

    @r.post("/v1/calypso/ack")
    async def post_ack(req: Request):
        phone(req)
        n = ack((await req.json()).get("ids", []))
        if n:
            store.log("calypso", f"phone took {n}")
        return {"ok": True, "removed": n}

    @r.post("/v1/calypso/key")
    async def post_key(req: Request):
        phone(req)
        try:
            publish(str((await req.json()).get("pem", "")))
        except ValueError as e:
            raise HTTPException(400, str(e))
        store.log("calypso", "public key published")
        return {"ok": True}

    return r
