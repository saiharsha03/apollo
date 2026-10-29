"""What Athena (or Iris, Atlas) changed on her own gets a Home card (Owner, 2026-10-28): not in a turn answering him,
but in a facts turn, heartbeat, routines run or taps. One card per run lists the changes; its Undo reverts the ones
that can be undone. The card has no until: it stays until he dismisses it. Undo comes back as a card_feedback fact
{card: "change:<n>", what: "Undo"}, handled here once (main._fact), never a fact for her.
"""
import hashlib
import json
import re
import time

from . import apps

# The tools that change something. Cards, notify and calls aren't changes; birthday_add makes its own card.
CHANGES = {"app_write", "app_sql", "app_create", "app_archive", "app_restore", "drives_fix", "phone_setting", "add_rule"}
BURST = 15 * 60     # an agent's own changes this close together (same run key) share a card
KEEP_DAYS = 60


def _book(store) -> dict:
    return store.setting("own_changes") or {"next": 1, "cards": {}}


def before(store, name: str, a: dict):
    """What Undo needs from before the call (phone_setting's old value), or None."""
    if name != "phone_setting":
        return None
    n = str(a.get("name", ""))
    if n == "sarvam_daily_cap":
        return {"value": store.setting("sarvam_daily_cap")}
    rows = store.q("SELECT data, at FROM outbox WHERE kind = 'setting' ORDER BY id DESC")
    last = next(((json.loads(r["data"])["value"], r["at"]) for r in rows if json.loads(r["data"]).get("name") == n), None)
    if n == "home_pins":     # he pins too: his latest home_pins fact wins when newer
        f = store.q("SELECT data, at FROM facts WHERE kind = 'home_pins' ORDER BY at DESC LIMIT 1")
        if f and (not last or f[0]["at"] / 1000 > last[1]):
            d = json.loads(f[0]["data"])
            last = (d.get("pins", ""), 0)
    return {"value": last[0]} if last else None


class Changed(Exception):
    """The file isn't what her write left: Undo would wipe a later edit."""


def _sha(app: str, path: str):
    """sha256 of the file's text now, or None when it is gone."""
    try:
        return hashlib.sha256(apps.read(app, path).encode("utf-8")).hexdigest()
    except apps.AppError:
        return None


def _item(name: str, a: dict, out: str, was) -> dict:
    """One line for the card, and how to undo it (None: can't automatically)."""
    why = f" ({str(a.get('why', ''))[:80]})" if a.get("why") else ""
    if name == "app_write":
        return {"what": f"{'changed' if a.get('_kept') else 'added'} {a['path']} in {a['app']}{why}",
                "undo": {"op": "write", "app": a["app"], "path": a["path"], "kept": a.get("_kept"),
                         "sha": hashlib.sha256(a["text"].encode("utf-8")).hexdigest()}}     # what her write put there, not the file now
    if name == "app_sql":
        return {"what": f"changed data in {a['app']}: {' '.join(str(a['sql']).split())[:80]}{why}", "undo": None}
    if name == "app_create":
        return {"what": f"made the app {a['name']}{why}", "undo": {"op": "archive", "app": a["name"]}}
    if name == "app_archive":
        return {"what": f"archived the app {a['name']}{why}", "undo": {"op": "restore", "app": a["name"]}}
    if name == "app_restore":
        return {"what": f"restored the app {a['name']}", "undo": {"op": "archive", "app": a["name"]}}
    if name == "drives_fix":
        m = re.search(r"\(fix (\d+)\)", out)
        return {"what": f"fixed Drives: {a.get('kind')}{why}", "undo": {"op": "drives", "fix": int(m.group(1))} if m else None}
    if name == "phone_setting":
        return {"what": f"set {a['name']} to {a['value']}{why}", "undo": {"op": "setting", "name": a["name"], "value": was["value"]} if was else None}
    return {"what": f"made {a.get('action')} {a.get('level')} or stricter{why}", "undo": None}     # add_rule: the phone only tightens


def record(store, who: str, run: str, name: str, a: dict, out: str, was=None):
    """A change made on her own: added to the run's card (a new one for a new run)."""
    if name not in CHANGES or (name == "app_sql" and not str(out).startswith("OK")) or str(out).startswith("Not done"):
        return
    item = _item(name, a, str(out), was)
    now = time.time()
    with store.lock:
        book = _book(store)
        book["cards"] = {k: c for k, c in book["cards"].items() if now - c["at"] < KEEP_DAYS * 86400}
        n = next((k for k, c in book["cards"].items() if c["run"] == run and c["who"] == who and not c["undone"]
                  and now - c["at"] < BURST), None)
        if n is None:
            n = str(book["next"])
            book["next"] += 1
            book["cards"][n] = {"run": run, "who": who, "items": [], "undone": False}
        c = book["cards"][n]
        c["items"].append(item)
        c["at"] = now
        store.set_setting("own_changes", book)
        _card(store, n, c)


def _card(store, n: str, c: dict, done: str = ""):
    items = c["items"]
    who, her = c["who"].title(), "his" if c["who"] == "atlas" else "her"
    title = f"{who} changed {len(items)} things on {her} own" if len(items) > 1 else f"{who} changed something on {her} own"
    lines = [f"- {i['what']}" + ("" if i["undo"] else " (can't undo automatically)") for i in items]
    if done:
        title, actions = "Undone: " + title, []
        lines.append(done)
    else:
        actions = ["Undo"] if any(i["undo"] for i in items) else []
    store.send("card", {"id": f"change:{n}", "title": title, "body": "\n".join(lines), "actions": actions, "app": "", "from": "apollo"})


def tap(tools, data: dict) -> bool:
    """His Undo on a change card: every undoable change in it reverted, once. True when the card was a change card."""
    m = re.fullmatch(r"change:(\d+)", str(data.get("card") or ""))
    if not m:
        return False
    if data.get("what") != "Undo":
        return True
    store, n = tools.store, m.group(1)
    with store.lock:
        book = _book(store)
        c = book["cards"].get(n)
        if not c or c["undone"]:
            return True
        c["undone"] = True
        store.set_setting("own_changes", book)
    done, failed = [], []
    for i in reversed(c["items"]):
        if not i["undo"]:
            continue
        try:
            _undo(tools, i["undo"])
            done.append(i["what"])
        except Changed:
            failed.append(f"{i['what']}: changed since; not undone")
        except Exception as e:      # one that can't go back (the app went away) doesn't stop the rest
            failed.append(f"{i['what']}: {e if isinstance(e, apps.AppError) else type(e).__name__}")
    store.send("apps", {"version": apps.version()})
    store.log("owner", f"undid change card {n}" + (f" ({len(failed)} failed)" if failed else ""))
    said = (["Undone: " + "; ".join(done)] if done else []) + (["Not undone: " + "; ".join(failed)] if failed else [])
    _card(store, n, c, "\n".join(said) or "Undone.")
    return True


def _undo(tools, u: dict):
    op = u["op"]
    if op == "write":
        if "sha" in u and _sha(u["app"], u["path"]) != u["sha"]:
            raise Changed()
        apps.revert(u["app"], u["path"], u.get("kept"), "owner")
    elif op == "archive":
        apps.archive(u["app"], "Owner tapped Undo", "owner")
    elif op == "restore":
        apps.restore(u["app"], "owner")
    elif op == "drives":
        out = tools.t_drives_fix("owner", {"kind": "undo", "fix": u["fix"], "why": "Owner tapped Undo"})
        if str(out).startswith("Not done"):
            raise apps.AppError(out)
    elif op == "setting":
        if u["name"] == "sarvam_daily_cap":
            tools.store.set_setting("sarvam_daily_cap", u["value"])
        else:
            tools.store.send("setting", {"name": u["name"], "value": u["value"], "why": "Owner tapped Undo", "from": "apollo"})
