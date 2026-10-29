"""Anything that breaks reaches Owner the same way (Owner, 2026-10-27: "like anything fails ... anything that happens"):
a high-priority popup and a Home card, once per outage (again after alert_repop_hours if a status row is still down),
both taken back with a quiet "Fixed" line once it recovers. Keys (docs/API.md): status:<component>, stall:<thread>,
tool:<name>, server:<route>.

Status rows (store.set_status) come here on their own; connection-like rows that blink wait alert_grace_minutes first.
"""
import re
import threading
import time

from . import config

# Rows that drop for a moment by nature (an agent's socket, a fetch, a wake): only an outage past the grace alerts.
GRACE = re.compile(r"^(iris|atlas)-|^(fcm|weather_now|nws|costs|athena|pan_feed|metron|box)$")
KEY = re.compile(r"sk-[A-Za-z0-9_-]{8,}|\b[A-Za-z0-9_-]{40,}\b")     # anything key-like never goes into a popup


def _clean(text: str) -> str:
    return KEY.sub("[hidden]", " ".join(str(text).split()))[:200]


def card_id(key: str) -> str:
    return "alert:" + key


def raise_(store, key: str, title: str, body: str, open: str = "", buttons=(), force: bool = False) -> bool:
    """Pops it (high priority) and puts its card on Home, unless this key already popped within alert_repop_hours (force:
    pops again, the old popup taken back). open: where the popup's tap goes; a thread:<id> one also gives the card an Open
    button to that chat. buttons: more card buttons ({label, do} or a label, which comes back as a card_feedback fact)."""
    title, body, now = _clean(title), _clean(body), time.time()
    with store.lock:
        live = store.setting("alerts_live") or {}
        a = live.get(key)
        if a and not force and now - a["popped"] < float(store.setting("alert_repop_hours")) * 3600:
            return False
        if a and force:
            store.send("notify", {"cancel": str(a["notify"]), "from": "apollo"})
        nid = store.send("notify", {"title": title, "body": body, "priority": "high", "open": open or "apollo", "from": "apollo"})
        actions = ([{"label": "Open", "do": open}] if open.startswith("thread:") else []) + list(buttons)
        store.send("card", {"id": card_id(key), "title": title, "body": body, "actions": actions, "app": "", "from": "apollo"})
        live[key] = {"title": title, "body": body, "popped": now, "since": (a or {}).get("since", now), "notify": nid}
        store.set_setting("alerts_live", live)
    store.log("apollo", f"alert {key}: {title}")
    return True


def clear(store, key: str):
    """It recovered: the card goes, the popup is taken back if still in the shade, and a quiet Fixed line says so."""
    with store.lock:
        pending = store.setting("alerts_pending") or {}
        if pending.pop(key, None):
            store.set_setting("alerts_pending", pending)
        live = store.setting("alerts_live") or {}
        a = live.pop(key, None)
        if not a:
            return
        store.set_setting("alerts_live", live)
        store.send("card_remove", {"id": card_id(key), "from": "apollo"})
        store.send("notify", {"cancel": str(a["notify"]), "from": "apollo"})
        store.send("notify", {"title": "Fixed: " + a["title"], "body": "", "priority": "low", "from": "apollo"})
    store.log("apollo", f"alert {key}: fixed")


def down(store, component: str, detail: str):
    """A status row went down: alert now, or after the grace for rows that blink."""
    key, title = "status:" + component, f"{component} is down"
    if not GRACE.search(component):
        raise_(store, key, title, detail)
        return
    with store.lock:
        pending = store.setting("alerts_pending") or {}
        if key not in pending:
            pending[key] = {"since": time.time(), "title": title, "body": _clean(detail)}
        else:
            pending[key]["body"] = _clean(detail)
        store.set_setting("alerts_pending", pending)


def sweep(store, now: float | None = None):
    """Once a minute: rows down past their grace alert; status rows still down after alert_repop_hours pop again."""
    now = now or time.time()
    grace = float(store.setting("alert_grace_minutes")) * 60
    for key, p in list((store.setting("alerts_pending") or {}).items()):
        if now - p["since"] >= grace and _still_down(store, key):
            with store.lock:
                pending = store.setting("alerts_pending") or {}
                pending.pop(key, None)
                store.set_setting("alerts_pending", pending)
            raise_(store, key, p["title"], p["body"])
    for key, a in (store.setting("alerts_live") or {}).items():
        if key.startswith("status:") and _still_down(store, key):
            raise_(store, key, a["title"], a["body"])      # pops only once the repop time has passed


def _still_down(store, key: str) -> bool:
    if not key.startswith("status:"):
        return True
    r = store.q("SELECT ok FROM status WHERE component = ?", (key[len("status:"):],))
    return bool(r) and not r[0]["ok"]


def box(store, now: float | None = None):
    """Her two boxes, from the file root's timer writes (deploy/box-status.sh; this server has no docker access).
    No file: the boxes aren't set up here, nothing to say."""
    p = config.BOX_STATUS
    try:
        text, at = p.read_text(), p.stat().st_mtime
    except OSError:
        return
    if (now or time.time()) - at > 300:
        store.set_status("box", False, "box status not updated in 5 minutes (its timer stopped?)")
        return
    stopped = [ln.split()[0] for ln in text.splitlines() if ln.strip() and ln.split()[-1] != "true"]
    store.set_status("box", not stopped, ("not running: " + ", ".join(stopped)) if stopped else "both running")


def start(store):
    def loop():
        while True:
            time.sleep(60)
            for fn in (box, sweep):
                try:
                    fn(store)
                except Exception as e:      # the type only
                    store.log("apollo", f"alerts {fn.__name__} failed: {type(e).__name__}")
    threading.Thread(target=loop, daemon=True, name="alerts").start()
