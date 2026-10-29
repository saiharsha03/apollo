"""apollo-server: the phone's REST routes, Athena's MCP endpoint, Iris's and Atlas's socket, and the facts timer.

Run: uvicorn apollo.main:app --host 127.0.0.1 --port 8090 (Caddy terminates TLS in front).
"""
import hashlib
import hmac
import json
import re
import threading
import time

from fastapi import FastAPI, HTTPException, Request
# Routes that await the body hand their blocking work (urllib, sockets, SQLite) to the thread pool: one uvicorn
# worker, so a 20 s call on the event loop would stall every other request.
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse, Response

from . import alerts, apps, bank, birthdays, changes, chat as chats, config, costs, devicecert, fcm, nws, routines, plans, securitylog, seed, weathernow
from .agents import Bus
from .brain import Brain, is_tap, wakes
from .store import Store
from .tools import TOOLS, Tools

store = Store()
bus = Bus(store)
tools = Tools(store, bus)
brain = Brain(store, bus)
bus.brain, bus.tools = brain, tools
clock = None     # the routines clock starts with the server
store.after_send = lambda kind="": threading.Thread(target=fcm.wake, args=(store, kind), daemon=True).start()
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
_failing: set = set()     # routes whose error alert is up: their next success clears it


@app.middleware("http")
async def errors(req: Request, call_next):
    """An uncaught error: logged, a 500, and one alert per route (the route's pattern, never its ids or query)."""
    try:
        r = await call_next(req)
    except Exception as e:
        route = getattr(req.scope.get("route"), "path", None) or req.url.path[:80]
        store.log("apollo", f"server error on {route}: {type(e).__name__}")
        alerts.raise_(store, f"server:{route}", "Apollo server error", f"{req.method} {route}: {type(e).__name__}")
        _failing.add(route)
        return JSONResponse({"detail": "server error"}, status_code=500)
    route = getattr(req.scope.get("route"), "path", None)
    if route in _failing and r.status_code < 400:      # the handler ran and answered: a 401 or 404 proves nothing
        _failing.discard(route)
        alerts.clear(store, f"server:{route}")
    return r


def _token_ok(req: Request, path) -> bool:
    h = req.headers.get("authorization", "")
    if not h.startswith("Bearer ") or not path.exists():
        return False
    return hmac.compare_digest(hashlib.sha256(h[7:].strip().encode()).hexdigest(), path.read_text().strip())


def phone(req: Request):
    if not _token_ok(req, config.DEVICE_TOKEN):
        raise HTTPException(401)


@app.on_event("startup")
def startup():
    config.WORKSPACE.mkdir(parents=True, exist_ok=True)
    global clock
    seed.plant()
    apps.prune_undo(changes.KEEP_DAYS)
    clock = routines.Clock(store, brain)
    bus.serve()
    # The agents' plugin lists tools from here (it has no other way to read the server's list).
    (config.RUN / "tools.json").write_text(__import__("json").dumps(TOOLS))
    __import__("os").chmod(config.RUN / "tools.json", 0o644)
    threading.Thread(target=_facts_timer, daemon=True, name="facts").start()
    restored.start(store)
    nws.start(store, brain, lambda k, d: clock and clock.on_fact(k, d))
    weathernow.start(store)
    plans.start(store)
    securitylog.start(store)
    calypso.republish()
    store.set_status("server", True, "started")
    alerts.start(store)
    # After a restart: threads with messages Athena hasn't seen yet go back in her queue; facts not yet sent go out.
    for t in {r["thread"] for r in store.undelivered("athena")}:
        brain.enqueue(t)
    threading.Thread(target=brain.deliver_facts, args=(True,), daemon=True).start()


def _facts_timer():
    """Athena's heartbeat; facts themselves go at once (Brain.facts_now)."""
    while True:
        time.sleep(60 * float(store.setting("heartbeat_minutes")))
        try:
            brain.heartbeat()
            store.set_status("heartbeat", True, "sent")
        except Exception as e:
            store.set_status("heartbeat", False, type(e).__name__)


# ---- pairing: the phone makes its own token and proves it holds the one-time code root printed (deploy/pair.sh) ----
_pair_fails = [0]


@app.post("/v1/pair")
async def pair(req: Request):
    b = await req.json()
    if not config.PAIR_CODE.exists():
        raise HTTPException(403, "no pairing open")
    code, until = config.PAIR_CODE.read_text().split()
    if time.time() > float(until) or _pair_fails[0] >= 5:
        config.PAIR_CODE.unlink()
        raise HTTPException(403, "pairing closed")
    if not hmac.compare_digest(str(b.get("code", "")).strip().lower().encode(), code.encode()):
        _pair_fails[0] += 1
        raise HTTPException(403, "wrong code")
    token = str(b.get("token", ""))
    if len(token) < 32:
        raise HTTPException(400, "token too short")
    # The phone's client certificate (mTLS), when it sent a request and the device CA exists (deploy/mtls.sh).
    csr = None
    if b.get("csr") and devicecert.ready():
        try:
            csr = devicecert.check(str(b["csr"]))
        except devicecert.BadRequest as e:
            raise HTTPException(400, str(e))
    config.DEVICE_TOKEN.write_text(hashlib.sha256(token.encode()).hexdigest())
    config.PAIR_CODE.unlink()
    _pair_fails[0] = 0
    store.log("apollo", "phone paired" + (" with a client certificate" if csr else ""))
    return {"ok": True, "cert": devicecert.sign(csr)} if csr else {"ok": True}


# ---- the phone ----
@app.post("/v1/facts")
async def facts(req: Request):
    phone(req)
    await run_in_threadpool(_facts, await req.json())
    return {"ok": True}


def _facts(body):
    wake = False
    for f in (body.get("facts") or [])[:500] if isinstance(body, dict) else []:
        try:
            _fact(f)
            wake = wake or (wakes(store, f) and not is_tap(store, f))     # his taps go on their own (Brain.tap)
        except Exception as e:      # one malformed fact is skipped, never a 500 that makes the phone resend them all
            store.log("apollo", f"fact skipped: {type(e).__name__}")
    if wake:                        # the rest waits for the heartbeat, which carries it
        brain.facts_now()


def _fact(f):
    kind, at, data = str(f.get("kind", ""))[:40], int(f.get("at", time.time() * 1000)), f.get("data") or {}
    fid = store.add_fact(kind, at, data, bool(f.get("urgent")), str(f["id"])[:80] if f.get("id") else None)
    if fid is None:
        return      # already here: the phone sent the batch again after a lost answer
    seed.ingest(kind, at, data)
    # Bank notifications also go to Iris (and a confirmed bank's fraud words ring him); his confirmed senders.
    if kind == "notification":
        bank.notification(store, bus, data)
    elif kind == "bank_sender":
        bank.sender_fact(store, data)
    elif kind == "card_feedback":
        brain.card_tap(data)        # his Retry on a stall card
        card = str(data.get("card") or "")
        if card.startswith(birthdays.CARD) and data.get("what") == "Undo":
            birthdays.undo(store, card)     # his Undo on a birthday Athena saved
            store.mark_sent([fid])
        if changes.tap(tools, data) or card.startswith("alert:"):
            store.mark_sent([fid])  # an alert card's tap is Apollo's to act on, not a fact for her facts turn
    if is_tap(store, {"kind": kind, "data": data}):
        brain.tap(fid, kind, at, data)
    if clock:
        clock.on_fact(kind, data)


@app.get("/v1/sync")
def sync(req: Request, since: int = 0):
    phone(req)
    store.phone_seen = time.time()
    out = store.outbox(since)
    return {"cursor": out[-1]["id"] if out else since, "outbox": out, "apps": {"version": apps.version()}}


@app.get("/v1/activity")
def activity(req: Request):
    """The latest activity per agent: the phone polls this every few seconds while Apollo is on screen."""
    phone(req)
    store.phone_seen = time.time()
    return store.setting("activity") or {}


@app.post("/v1/ack")
async def ack(req: Request):
    phone(req)
    store.ack([int(i) for i in (await req.json()).get("ids", [])])
    return {"ok": True}


@app.post("/v1/chat")
async def chat(req: Request):
    phone(req)
    return await run_in_threadpool(_chat, await req.json())


def _chat(b):
    text = str(b.get("text", "")).strip()
    upload = int(b["upload"]) if b.get("upload") else None
    if upload and not store.upload(upload):
        raise HTTPException(400, "no such upload")
    if not text and not upload:
        raise HTTPException(400, "empty")
    to = (b.get("to") or "").lower()
    if to not in ("", "athena", "iris", "atlas", "group"):
        raise HTTPException(400, "to: athena, iris, atlas or group")
    if b.get("thread"):
        t = int(b["thread"])
        if not store.q("SELECT 1 FROM threads WHERE id = ?", (t,)):
            raise HTTPException(404, "no such thread")
        to = store.thread_agent(t)                # a conversation goes to the agent it belongs to, whatever to says
    else:
        to = to or "athena"
        t = store.thread(b.get("title") or text[:60], agent=to)
    reply = int(b["reply_to"]) if b.get("reply_to") else None
    if reply and (store.message(reply) or {}).get("thread") != t:
        reply = None                               # a quote only from the same thread
    source = "voice" if b.get("source") == "voice" else None     # the Ask sheet and Ossa's Reply send speech
    mid = store.post(t, "owner", text, None if to == "group" else to, reply_to=reply, upload=upload, source=source)
    # The group chat (Owner + Athena + Iris + Atlas): his words reach all three; each answers its part or stays quiet.
    for agent in (("athena", "iris", "atlas") if to == "group" else (to,)):
        bus.deliver(agent, t, mid)
    return {"thread": t, "message": mid}


def _his(mid, own=False):
    """A message he can act on (react to any; edit or delete only his own, not already deleted)."""
    m = store.message(mid)
    if not m or (own and (m["sender"] != "owner" or m["deleted"])):
        raise HTTPException(404)
    return m


@app.post("/v1/messages/{mid}/react")
async def react(req: Request, mid: int):
    phone(req)
    b = await req.json()
    emoji = str(b.get("emoji", "")).strip()[:16]
    if not emoji or _his(mid)["deleted"]:
        raise HTTPException(400, "no emoji")
    await run_in_threadpool(chats.reacted, store, bus, mid, emoji, b.get("on", True) is not False)
    return {"ok": True}


@app.post("/v1/messages/{mid}/edit")
async def edit(req: Request, mid: int):
    phone(req)
    _his(mid, own=True)
    text = str((await req.json()).get("text", "")).strip()
    if not text:
        raise HTTPException(400, "empty")
    await run_in_threadpool(chats.edited, store, bus, mid, text)
    return {"ok": True}


@app.post("/v1/messages/{mid}/delete")
def delete(req: Request, mid: int):
    phone(req)
    _his(mid, own=True)
    chats.deleted(store, bus, mid)
    return {"ok": True}


@app.post("/v1/uploads")
async def upload(req: Request, name: str = "file"):
    """His photo or file for a chat: the raw bytes, its type in Content-Type. Then /v1/chat with the upload id."""
    phone(req)
    body = await req.body()
    if len(body) > int(store.setting("upload_max_mb")) * 1024 * 1024:
        raise HTTPException(413, "too big")
    if not body:
        raise HTTPException(400, "empty")
    return {"upload": chats.save_upload(store, name, req.headers.get("content-type", "").split(";")[0].strip(), body)}


@app.get("/v1/uploads/{uid}")
def upload_file(req: Request, uid: int):
    phone(req)
    f = store.upload(uid)
    if not f:
        raise HTTPException(404)
    return FileResponse(f["path"], media_type=f["mime"], filename=f["name"])


@app.post("/v1/brain_key")
async def brain_key(req: Request):
    phone(req)
    key = str((await req.json()).get("key", "")).strip()
    if not key.startswith("sk-ant-"):
        raise HTTPException(400, "not an Anthropic key")
    config.ANTHROPIC_KEY.write_text(key)
    config.ANTHROPIC_KEY.chmod(0o600)
    brain.client = None
    store.log("apollo", "brain key received from the phone")
    return {"ok": True}


@app.post("/v1/import/zeus-apps")
async def import_zeus_apps(req: Request):
    """Zeus's old mini apps from the phone, kept as a zip for the move (nothing is unpacked or run here)."""
    phone(req)
    body = await req.body()
    if len(body) > 64 * 1024 * 1024:   # the zip is held in memory; a guard for the server's RAM
        raise HTTPException(413)
    d = config.DATA / "import"
    d.mkdir(exist_ok=True)
    await run_in_threadpool((d / f"zeus-apps-{int(time.time())}.zip").write_bytes, body)
    store.log("apollo", f"zeus apps zip received ({len(body)} bytes)")
    return {"ok": True}


@app.post("/v1/box_key")
async def box_key(req: Request):
    """The self-hosted environment's key, pasted once from the Console into the phone. Only the doorman box reads it."""
    phone(req)
    key = str((await req.json()).get("key", "")).strip()
    if not key.startswith("sk-ant-"):
        raise HTTPException(400, "not an environment key")
    p = config.DATA / "box-envkey"
    p.write_text(key)
    p.chmod(0o600)
    store.log("apollo", "box environment key received")
    return {"ok": True}


@app.post("/v1/admin_key")
async def admin_key(req: Request):
    """The Anthropic admin key (Calypso slot anthropic_admin), for the Usage page's real costs."""
    phone(req)
    key = str((await req.json()).get("key", "")).strip()
    if not key.startswith("sk-ant-admin"):
        raise HTTPException(400, "not an admin key")
    config.ADMIN_KEY.write_text(key)
    config.ADMIN_KEY.chmod(0o600)
    store.log("apollo", "admin key received from the phone")
    return {"ok": True}


@app.post("/v1/tomtom_key")
async def tomtom_key(req: Request):
    """Owner's TomTom key (Calypso slot tomtom), for the agents' tomtom tool."""
    phone(req)
    key = str((await req.json()).get("key", "")).strip()
    if not re.fullmatch(r"[A-Za-z0-9]{16,64}", key):
        raise HTTPException(400, "not a TomTom key")
    config.TOMTOM_KEY.write_text(key)
    config.TOMTOM_KEY.chmod(0o600)
    store.log("apollo", "TomTom key received from the phone")
    return {"ok": True}


@app.post("/v1/ny511_key")
async def ny511_key(req: Request):
    """His 511NY key (Calypso slot ny511), for the agents' roads tool."""
    phone(req)
    key = str((await req.json()).get("key", "")).strip()
    if not re.fullmatch(r"[A-Za-z0-9-]{16,64}", key):
        raise HTTPException(400, "not a 511NY key")
    config.NY511_KEY.write_text(key)
    config.NY511_KEY.chmod(0o600)
    store.log("apollo", "511NY key received from the phone")
    return {"ok": True}


@app.get("/v1/threads")
def threads(req: Request):
    phone(req)
    return store.threads()


@app.post("/v1/threads/{t}/{what}")
def archive(req: Request, t: int, what: str):
    """His archive: put a conversation away, or bring it back (system threads can't be archived)."""
    phone(req)
    if what not in ("archive", "restore") or not store.archive(t, what == "archive"):
        raise HTTPException(404)
    return {"ok": True}


@app.get("/v1/threads/{t}")
def thread(req: Request, t: int, since: int = 0, before: int = 0, limit: int = 200):
    phone(req)
    return store.messages(t, since, max(1, min(limit, 500)), before)


@app.get("/v1/tiles/{z}/{x}/{y}.png")
def tile(req: Request, z: int, x: int, y: int):
    """A dark street map tile for a page's map (tiles.py): the server's TomTom key, kept on disk."""
    phone(req)
    from . import tiles, tomtom
    try:
        body = tiles.get(store, z, x, y)
    except ValueError:
        raise HTTPException(404)
    except tomtom.Refused as e:
        raise HTTPException(429 if str(e).startswith("daily") else 503, str(e))
    return Response(body, media_type="image/png", headers={"Cache-Control": "private, max-age=604800"})


@app.get("/v1/apps/manifest")
def manifest(req: Request):
    phone(req)
    return apps.phone_manifest()


@app.get("/v1/apps/file")
def app_file(req: Request, app: str = "", path: str = ""):
    phone(req)
    try:
        return FileResponse(apps.phone_file(app, path))
    except apps.AppError:
        raise HTTPException(404)


@app.get("/v1/apps/archived")
def archived(req: Request):
    phone(req)
    return apps.archived()


@app.post("/v1/apps/{name}/archive")
def archive_app(req: Request, name: str):
    """His one-tap kill: the app moves to the archive and can be restored."""
    phone(req)
    try:
        r = apps.archive(name, "his tap", "owner")
    except apps.AppError as e:
        raise HTTPException(404, str(e))
    store.send("apps", {"version": apps.version()})
    return {"ok": True, "detail": r}


@app.post("/v1/apps/{name}/restore")
def restore_app(req: Request, name: str):
    """His tap on an archived app: it comes back as it was."""
    phone(req)
    try:
        r = apps.restore(name, "owner")
    except apps.AppError as e:
        raise HTTPException(404, str(e))
    store.send("apps", {"version": apps.version()})
    return {"ok": True, "detail": r}


@app.post("/v1/actions/{oid}")
async def action_result(req: Request, oid: int):
    phone(req)
    b = await req.json()
    store.x("INSERT INTO actions(outbox, ok, detail, at) VALUES(?, ?, ?, ?)", (oid, 1 if b.get("ok") else 0, str(b.get("detail", ""))[:300], time.time()))
    asked = json.loads((store.q("SELECT data FROM outbox WHERE id = ? AND kind = 'action'", (oid,)) or [{"data": "{}"}])[0]["data"])
    d, at = {"action": oid, "ok": bool(b.get("ok")), "detail": str(b.get("detail", ""))[:300]}, int(time.time() * 1000)
    birthdays.action_result(store, oid, bool(b.get("ok")), str(b.get("detail", "")))
    if asked.get("facts_turn"):     # asked by her facts turn (phone_do, birthday_add), whose why can carry other people's text: back there, no why
        store.add_fact("action_result", at, d, False)
        brain.facts_now()
    else:                           # asked by a trusted run: his answer reaches her now, in his taps thread, with its why
        d |= {"asked": asked.get("action", ""), "why": asked.get("why", "")}
        brain.tap(store.add_fact("action_result", at, d, False), "action_result", at, d)

    return {"ok": True}


@app.get("/v1/status")
def status(req: Request):
    phone(req)
    real = None
    try:
        real = costs.report()
    except Exception:
        pass
    return {"brain": brain.active(), "usage": tools.run("apollo", "usage", {}), "costs": real, "status": store.statuses(), "agents": bus.connected()}


# Lemnos (health, read-only files), Mnemosyne's sealed backups and Prometheus's signed updates: their own modules.
from . import backup, lemnos, prometheus  # noqa: E402
app.include_router(lemnos.router(phone, store))
app.include_router(backup.router(phone))
app.include_router(prometheus.router(phone))
# Restored from Zeus: Spotify's sign-in, Clio's upload, Pan, the Zeus data import (restored.py).
from . import restored, spotify  # noqa: E402
app.include_router(restored.router(phone, store))
app.include_router(spotify.router(phone))

# Calypso drops: sealed by Iris or Atlas, ciphertext only, on their way to the phone.
from . import calypso  # noqa: E402
app.include_router(calypso.router(phone, store))

# Sarvam voice: Athena's words as speech, his recordings as words (the phone falls back to Android's voice).
from . import sarvam  # noqa: E402
app.include_router(sarvam.router(phone, store))


@app.post("/v1/fcm")
async def fcm_token(req: Request):
    phone(req)
    tok = str((await req.json()).get("token", ""))[:400]
    if tok:
        store.x("INSERT OR REPLACE INTO fcm(token, at) VALUES(?, ?)", (tok, time.time()))
    return {"ok": True}


# ---- Athena's MCP endpoint (streamable HTTP, JSON answers, stateless) ----
MCP_VERSION = "2025-06-18"


@app.post("/mcp")
async def mcp(req: Request):
    # The calling session's own token says what it may do, never which run happens to be open (fail closed: facts).
    trusted = _token_ok(req, config.MCP_TOKEN_TRUSTED)
    if not trusted and not _token_ok(req, config.MCP_TOKEN):
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    msg = await req.json()
    if isinstance(msg, list):
        return JSONResponse(await run_in_threadpool(lambda: [r for r in (_rpc(m, not trusted) for m in msg) if r is not None]))
    r = await run_in_threadpool(_rpc, msg, not trusted)
    return Response(status_code=202) if r is None else JSONResponse(r)


@app.get("/mcp")
def mcp_get():
    return Response(status_code=405)   # no server-initiated stream


def _rpc(m: dict, facts_turn: bool = True):
    mid, method, params = m.get("id"), m.get("method", ""), m.get("params") or {}
    if mid is None:
        return None     # notifications (initialized, cancelled)
    if method == "initialize":
        res = {"protocolVersion": params.get("protocolVersion", MCP_VERSION), "capabilities": {"tools": {}},
               "serverInfo": {"name": "apollo", "version": "1.0.0"}}
    elif method == "tools/list":
        res = {"tools": TOOLS}
    elif method == "tools/call":
        # Calls from a facts session (or with no trusted token) get the facts turn's limits (tools.FACTS_ALLOWED).
        # A call while a facts, routines or taps run is open is on her own (the facts token always is): changes get a card.
        own = getattr(brain, "own_run", lambda: None)()
        content = tools.content("athena", params.get("name", ""), params.get("arguments") or {}, facts_turn=facts_turn,
                                own=own or ("facts" if facts_turn else None))
        res = {"content": content, "isError": content[0].get("text", "").startswith(("Not done", "Unknown tool"))}
    elif method == "ping":
        res = {}
    else:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"no method {method}"}}
    return {"jsonrpc": "2.0", "id": mid, "result": res}
