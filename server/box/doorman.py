"""The doorman: the only process that holds Apollo's environment key. It long-polls Anthropic for Athena's work items
and hands each one (ids and that item's one-time secret, nothing else) to her box (Owner, 2026-10-26: two boxes).

It runs none of her tools, so nothing she runs can reach the key. It runs as its own uid (BOX_DOOR_UID in
box-install.sh) and owns the socket and its directory, so code in the box can't replace the socket (scan F3): the box
connects in, only a peer with the box's uid (SO_PEERCRED) is taken, and only one at a time (sandbox.py holds it).

The box has no route out (an internal docker network). The doorman is its one way to the internet: a CONNECT proxy
that opens tunnels to api.anthropic.com:443 and nothing else (scan F1)."""
import asyncio
import json
import logging
import os
import re
import socket
import struct

SOCKET = os.environ.get("BOX_SOCKET", "/run/box/box.sock")
KEY_FILE = os.environ.get("BOX_KEY_FILE", "/run/secrets/envkey")
BOX_UID = int(os.environ.get("BOX_UID", "10021"))
PROXY_PORT = int(os.environ.get("BOX_PROXY_PORT", "3128"))
ALLOWED = {("api.anthropic.com", 443)}
log = logging.getLogger("doorman")
box = {"w": None}      # the one connected box


def handoff(work, environment_id: str) -> dict | None:
    """What the box gets for one claimed item. None for anything but a session."""
    if getattr(work.data, "type", "session") != "session":
        return None
    return {"work_id": work.id, "session_id": work.data.id, "environment_id": environment_id, "secret": work.secret}


def peer_uid(w) -> int | None:
    sock = w.get_extra_info("socket")
    try:
        _pid, uid, _gid = struct.unpack("3i", sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i")))
        return uid
    except (AttributeError, OSError):
        return None


def take_box(uid, current) -> bool:
    """A box connection is kept only from the box's uid and only while no other box is connected."""
    return uid == BOX_UID and (current is None or current.is_closing())


async def on_box(reader, writer):
    uid = peer_uid(writer)
    if not take_box(uid, box["w"]):
        log.warning("refused a socket peer (uid %s)", uid)
        writer.close()
        return
    box["w"] = writer
    log.info("the box connected")
    try:
        await reader.read()        # the box never writes; this returns when it hangs up
    finally:
        if box["w"] is writer:
            box["w"] = None
        writer.close()


async def give(item: dict) -> bool:
    w = box["w"]
    if w is None or w.is_closing():
        log.error("no box to take %s", item["session_id"])
        return False
    try:
        w.write((json.dumps(item) + "\n").encode())
        await w.drain()
        return True
    except OSError as e:
        log.error("the box didn't take %s (%s)", item["session_id"], type(e).__name__)
        return False


def proxy_target(line: bytes):
    """(host, port) from a CONNECT request line when it is allowed, else None."""
    parts = line.decode("latin-1").strip().split()
    if len(parts) != 3 or parts[0] != "CONNECT":
        return None
    host, _, port = parts[1].rpartition(":")
    try:
        target = (host.lower(), int(port))
    except ValueError:
        return None
    return target if target in ALLOWED else None


async def _pipe(r, w):
    try:
        while data := await r.read(65536):
            w.write(data)
            await w.drain()
    except OSError:
        pass
    finally:
        w.close()


async def on_proxy(reader, writer):
    try:
        head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
        target = proxy_target(head.split(b"\r\n", 1)[0])
        if not target:
            writer.write(b"HTTP/1.1 403 Forbidden\r\n\r\n")
            await writer.drain()
            writer.close()
            return
        ur, uw = await asyncio.open_connection(*target)
        writer.write(b"HTTP/1.1 200 Connection established\r\n\r\n")
        await writer.drain()
        await asyncio.gather(_pipe(reader, uw), _pipe(ur, writer))
    except (OSError, asyncio.TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
        writer.close()


async def serve():
    if os.path.exists(SOCKET):
        os.unlink(SOCKET)
    door = await asyncio.start_unix_server(on_box, SOCKET)
    os.chmod(SOCKET, 0o666)        # the uid check above is the gate; the directory is the doorman's alone
    proxy = await asyncio.start_server(on_proxy, "0.0.0.0", PROXY_PORT)
    return door, proxy


async def main():
    from anthropic import AsyncAnthropic
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    logging.getLogger("anthropic").setLevel(logging.WARNING)
    logging.getLogger("httpx2").setLevel(logging.WARNING)   # one line per poll otherwise
    env_id = os.environ["BOX_ENVIRONMENT_ID"]
    key = open(KEY_FILE).read().strip()
    if not re.fullmatch(r"^env_[A-Za-z0-9]{1,100}$", env_id) or not key.startswith("sk-ant-"):
        raise SystemExit("bad environment id or key")
    servers = await serve()   # noqa: F841  (kept open for the life of the process)
    backoff = 1
    while True:
        try:
            async with AsyncAnthropic(auth_token=key) as client:
                # auto_stop=False: the box's handle_item stops each item when it's done.
                async for work in client.beta.environments.work.poller(environment_id=env_id, environment_key=key, auto_stop=False):
                    item = handoff(work, env_id)
                    if item:
                        await give(item)
                    backoff = 1
        except Exception as e:     # the type only: an error's text can quote a request
            log.warning("poll failed (%s); again in %ss", type(e).__name__, backoff)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 60)   # keeps a dead network from hammering Anthropic's queue; never gives up


if __name__ == "__main__":
    asyncio.run(main())
