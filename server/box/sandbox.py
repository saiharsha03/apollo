"""Athena's box: where her bash and file tools run, with /workspace kept on the VPS between sessions. It never holds
the environment key: each job arrives from the doorman with its own one-time secret and runs as its own task with
the SDK's EnvironmentWorker.handle_item(), so jobs run side by side. The doorman owns the socket (its directory is
read-only here); the box connects in and holds the one connection. Its only way out is the doorman's proxy
(HTTPS_PROXY, set by box-install.sh), which reaches api.anthropic.com and nothing else."""
import asyncio
import json
import logging
import os

SOCKET = os.environ.get("BOX_SOCKET", "/run/box/box.sock")
WORKDIR = os.environ.get("BOX_WORKDIR", "/workspace")
log = logging.getLogger("box")
running: set = set()


async def job(item: dict):
    from anthropic import AsyncAnthropic
    from anthropic.lib.environments import EnvironmentWorker
    sid = item.get("session_id", "?")
    try:
        # No key on the client: every call rides this item's own sessions token.
        async with AsyncAnthropic(api_key=None, auth_token=None) as client:
            await EnvironmentWorker(client, workdir=WORKDIR).handle_item(
                work_id=item["work_id"], environment_id=item["environment_id"], session_id=sid, work_secret=item["secret"])
        log.info("session %s done", sid)
    except Exception as e:   # types, and where it happened (file and line), never the messages: they can quote a request
        parts = getattr(e, "exceptions", None) or [e]
        log.error("session %s failed: %s", sid, "; ".join(f"{type(x).__name__} at {_where(x)}" for x in parts))


def _where(e) -> str:
    tb = e.__traceback__
    while tb and tb.tb_next:
        tb = tb.tb_next
    return f"{tb.tb_frame.f_code.co_filename.rsplit('/', 1)[-1]}:{tb.tb_lineno}" if tb else "?"


def take(line: bytes):
    try:
        item = json.loads(line)
    except ValueError:
        return
    if not isinstance(item, dict) or not all(isinstance(item.get(k), str) for k in ("work_id", "session_id", "environment_id", "secret")):
        return
    t = asyncio.create_task(job(item))
    running.add(t)
    t.add_done_callback(running.discard)


async def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    logging.getLogger("anthropic").setLevel(logging.WARNING)
    logging.getLogger("httpx2").setLevel(logging.WARNING)   # one line per poll otherwise
    os.makedirs(WORKDIR, exist_ok=True)
    while True:
        try:
            reader, writer = await asyncio.open_unix_connection(SOCKET)
            log.info("connected to the doorman")
            while line := await reader.readline():
                take(line)
            writer.close()
        except OSError as e:
            log.warning("doorman not there (%s)", type(e).__name__)
        await asyncio.sleep(2)


if __name__ == "__main__":
    asyncio.run(main())
