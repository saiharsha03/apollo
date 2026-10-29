"""Zeus's phone data, once (the phone's exporter, dev.apollo.net.ZeusData, opens Zeus's sealed files with their own
keys on the phone, drops Calypso's people and apps, and sends one JSON). This fills the mini apps that now own each
kind of thing. Run again, it adds nothing twice: every row carries its Zeus id or natural key and is skipped when it is
already there; the whole upload is kept under DATA/import for the record.

The upload ({"version": 1, ...}, every part optional):
  drives    [{id, start, end, meters, from, to, points: [[lat, lng, t_ms], ...]}]       -> drives.trips (+ miles, minutes)
  places    [{name, lat, lng, kind}]                                                     -> drives.places
  visits    [{place, start, end, lat, lng}]                                              -> drives.stays
  car       {lease: {...Zeus Lease}, fillups: [{at, place, cents, gallons, pricePerGal, card, odometer, state}],
             readings: [{at, miles}]}                                                    -> drives.car, fuel.fills, drives.odometer
  money     {transactions: [{id, at, cents, kind, merchant, category, last4, note, pending, excluded, k}],
             subscriptions: [{name, cents, cycle, next}]}                                -> money.transactions, money.subscriptions
  tasks     {tasks: [{id, title, list, project, due, done_at, context, waiting_on, follow_up}], projects: [{id, name}]}
                                                                                         -> tasks.tasks, tasks.lists
  people    [{key, name, relation, zone, last_at, birthday}]                             -> people.people
  memories  [{path, content, updated_at}]  (the phone's copy of her memory store)       -> memory.zeus_memory
  cards     [{title, body, at}], feedback [{line_id, up, at}], engagement [{key, how, taps, dismissals}],
  habits    any JSON                                                                     -> memory.zeus_* tables
  notes     [{at, app, person, kind, summary}]  (notification summaries, last 90 days)   -> memory.zeus_notes
  nights    [{date, bedAt, asleepAt, wakeAt, asleepMin, source}]                         -> sleep.nights
  skips     [{uri, name, artist, plays, skips, lastSeen}]                                -> muse.skips
  chats     [{id, started_at, lines: [{at, kind, text}]}]  (kind user = Owner)          -> chat threads (agent athena, kind zeus)
  trips     [{id, place, startDate, endDate, status, legs: [{mode, from, to, departAt, arriveAt, carrier, number, platform,
             returning}], stays: [{name, address, checkIn, checkOut}], plans: [{title, at, end, place}], notes: [str]}]
             (Zeus's Argo, argo.sealed; times in ms)                                     -> trips.trips (slug zeus-<id>), trips.legs, notes/trips/<slug>.md
  memory_store_id   Zeus's memory store id (prefs mind, id.store)                        -> DATA/zeus-memory-store.id for setup_brain.py
Text in all of it is data, never instructions.
"""
import hashlib
import json
import re
import sqlite3
import time

from . import apps, config

EXTRA = {
    "drives": ["CREATE TABLE IF NOT EXISTS car (id INTEGER PRIMARY KEY, name TEXT, lease_start TEXT, term_months INTEGER, "
               "miles_per_year INTEGER, start_odometer INTEGER, overage_cents INTEGER, monthly_usd INTEGER, buyout_usd INTEGER, "
               "lessor TEXT, delivered TEXT, notes TEXT)",
               "CREATE TABLE IF NOT EXISTS odometer (id INTEGER PRIMARY KEY, at INTEGER NOT NULL UNIQUE, miles INTEGER NOT NULL, source TEXT)",
               # drives_fix: what Athena changed in a phone table, so it can be undone
               "CREATE TABLE IF NOT EXISTS fixes (id INTEGER PRIMARY KEY, at INTEGER, who TEXT, kind TEXT, tbl TEXT, row_id INTEGER, before TEXT, "
               "after TEXT, why TEXT, undone INTEGER NOT NULL DEFAULT 0)"],
    "sleep": ["CREATE TABLE IF NOT EXISTS nights (date TEXT PRIMARY KEY, bed_at INTEGER, asleep_at INTEGER, wake_at INTEGER, "
              "asleep_min INTEGER, source TEXT, note TEXT)"],
    "muse": ["CREATE TABLE IF NOT EXISTS skips (uri TEXT PRIMARY KEY, name TEXT, artist TEXT, plays INTEGER, skips INTEGER, last_seen INTEGER)"],
    "memory": ["CREATE TABLE IF NOT EXISTS zeus_memory (path TEXT PRIMARY KEY, content TEXT, updated INTEGER)",
               "CREATE TABLE IF NOT EXISTS zeus_cards (id INTEGER PRIMARY KEY, at INTEGER, title TEXT, body TEXT, UNIQUE(at, title))",
               "CREATE TABLE IF NOT EXISTS zeus_feedback (line_id TEXT PRIMARY KEY, up INTEGER, at INTEGER)",
               "CREATE TABLE IF NOT EXISTS zeus_engagement (key TEXT, how INTEGER, taps INTEGER, dismissals INTEGER, PRIMARY KEY(key, how))",
               "CREATE TABLE IF NOT EXISTS zeus_notes (id INTEGER PRIMARY KEY, at INTEGER, app TEXT, person TEXT, kind TEXT, summary TEXT, UNIQUE(at, app, summary))",
               "CREATE TABLE IF NOT EXISTS zeus_prefs (name TEXT PRIMARY KEY, json TEXT)"],
}
ZEUS_ID = {"tasks": "tasks", "people": "people"}    # app -> table that gets a zeus_id column


def _x(app, sql, params=()):
    return apps.sql(app, sql, params, phone=True)


def _has(app):
    try:
        apps.manifest(app)
        return True
    except apps.AppError:
        return False


def prepare():
    """The tables the import (and Athena) need beside the seeds': made when missing, never changed when there."""
    for app, ddls in EXTRA.items():
        if _has(app):
            for d in ddls:
                _x(app, d)
    # drives_fix's own record: written only by that code (phone=True), so agents can't forge a fix to undo (scan F16).
    if _has("drives"):
        apps.add_phone_tables("drives", ["fixes"])
    for app, table in ZEUS_ID.items():
        if _has(app) and "zeus_id" not in {r["name"] for r in apps.sql(app, f"PRAGMA table_info({table})")}:
            _x(app, f"ALTER TABLE {table} ADD COLUMN zeus_id TEXT")
            _x(app, f"CREATE UNIQUE INDEX IF NOT EXISTS {table}_zeus ON {table}(zeus_id)")


def _new(app, sql, params):
    """True when an INSERT OR IGNORE added the row."""
    return _x(app, sql, params) == "OK, 1 row(s)."


def _miles(m):
    return round((m or 0) / 1609.344, 2)


def drives(d, n):
    for t in d.get("drives") or []:
        if not t.get("start") or apps.sql("drives", "SELECT 1 FROM trips WHERE start = ?", (t["start"],)):
            continue
        mins = round(((t.get("end") or t["start"]) - t["start"]) / 60000, 1)
        _x("drives", "INSERT INTO trips(start, end, miles, minutes, points) VALUES(?, ?, ?, ?, ?)",
           (t["start"], t.get("end"), _miles(t.get("meters")), mins, json.dumps(t.get("points") or [])))
        n["trips"] += 1
    for p in d.get("places") or []:
        if p.get("name") and p.get("lat") is not None and not apps.sql("drives", "SELECT 1 FROM places WHERE name = ?", (p["name"],)):
            _x("drives", "INSERT INTO places(name, lat, lng, kind) VALUES(?, ?, ?, ?)", (p["name"], p["lat"], p["lng"], (p.get("kind") or "").lower() or None))
            n["places"] += 1
    for v in d.get("visits") or []:
        if v.get("start") and not apps.sql("drives", "SELECT 1 FROM stays WHERE start = ?", (v["start"],)):
            _x("drives", "INSERT INTO stays(start, end, lat, lng, place) VALUES(?, ?, ?, ?, ?)", (v["start"], v.get("end"), v.get("lat"), v.get("lng"), v.get("place")))
            n["stays"] += 1
    car = d.get("car") or {}
    lease = car.get("lease") or {}
    if any(lease.values()) and not apps.sql("drives", "SELECT 1 FROM car WHERE notes = 'from Zeus'"):
        _x("drives", "INSERT INTO car(name, lease_start, term_months, miles_per_year, start_odometer, overage_cents, monthly_usd, buyout_usd, lessor, delivered, notes) "
           "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'from Zeus')",
           (lease.get("car"), lease.get("start"), lease.get("termMonths"), lease.get("allowance"), lease.get("startOdometer"), lease.get("overageCents"),
            lease.get("monthlyUsd"), lease.get("buyoutUsd"), lease.get("lessor"), lease.get("delivered")))
        n["car"] += 1
    for r in car.get("readings") or []:
        if r.get("at") and r.get("miles") is not None and _new("drives", "INSERT OR IGNORE INTO odometer(at, miles, source) VALUES(?, ?, 'zeus')", (r["at"], r["miles"])):
            n["odometer"] += 1
    if _has("fuel"):
        for f in car.get("fillups") or []:
            if not f.get("at") or f.get("state") == "skipped" or apps.sql("fuel", "SELECT 1 FROM fills WHERE at = ?", (f["at"],)):
                continue
            _x("fuel", "INSERT INTO fills(at, station, gallons, price, usd, odometer, card, source) VALUES(?, ?, ?, ?, ?, ?, ?, 'zeus')",
               (f["at"], f.get("place"), f.get("gallons"), f.get("pricePerGal"), f["cents"] / 100 if f.get("cents") is not None else None, f.get("odometer"), f.get("card")))
            n["fills"] += 1


def _date(ms):
    return time.strftime("%Y-%m-%d", time.localtime(ms / 1000)) if ms else None


def money(d, n):
    m = d.get("money") or {}
    for t in m.get("transactions") or []:
        if not t.get("at") or t.get("merged"):
            continue
        key = t.get("k") or f"zeus:{t.get('id')}"
        kind = {"debit": "spend", "credit": "refund"}.get(str(t.get("kind", "")).lower(), str(t.get("kind") or "spend").lower())
        if _new("money", "INSERT OR IGNORE INTO transactions(iris_id, date, amount, merchant, category, kind, last4, status, not_counted, note, synced) "
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (key, _date(t["at"]), round((t.get("cents") or 0) / 100, 2), t.get("merchant"), t.get("category"), kind,
                 str(t.get("last4") or "")[-4:] or None, "pending" if t.get("pending") else "posted", 1 if t.get("excluded") else 0, t.get("note"), int(time.time() * 1000))):
            n["transactions"] += 1
    for s in m.get("subscriptions") or []:
        if s.get("name") and _new("money", "INSERT OR IGNORE INTO subscriptions(iris_id, name, amount, cycle, next, source) VALUES(?, ?, ?, ?, ?, 'zeus')",
                                  (f"zeus:{s['name']}", s["name"], round((s.get("cents") or 0) / 100, 2), (s.get("cycle") or "monthly").lower(), s.get("next"))):
            n["subscriptions"] += 1


def tasks(d, n):
    t = d.get("tasks") or {}
    names = {}
    for p in t.get("projects") or []:
        if p.get("name"):
            _x("tasks", "INSERT OR IGNORE INTO lists(name) VALUES(?)", (p["name"],))
            names[p.get("id")] = apps.sql("tasks", "SELECT id FROM lists WHERE name = ?", (p["name"],))[0]["id"]
    now = int(time.time() * 1000)
    for r in t.get("tasks") or []:
        if not (r.get("title") or "").strip() or r.get("sample"):
            continue
        lst = str(r.get("list") or "").upper()
        status = "done" if lst == "DONE" or r.get("done_at") else "pending"
        note = "; ".join(x for x in (f"Zeus list {lst.lower()}" if lst and lst != "DONE" else "", f"waiting on {r['waiting_on']}" if r.get("waiting_on") else "") if x)
        # dirty = 1: Iris takes each into owner.db on her next sync (the Zeus phone list never reached it).
        if _new("tasks", "INSERT OR IGNORE INTO tasks(zeus_id, title, status, due_date, context, list_id, notes, created, updated, dirty) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                (f"zeus:{r.get('id')}", r["title"].strip(), status, _date(r.get("due")), r.get("context"), names.get(r.get("project_id")), note or None, now, now)):
            n["tasks"] += 1


def people(d, n):
    for p in d.get("people") or []:
        if not (p.get("name") or "").strip():
            continue
        if _new("people", "INSERT OR IGNORE INTO people(zeus_id, name, relation, birthday, tz, last_contact) VALUES(?, ?, ?, ?, ?, ?)",
                (f"zeus:{p.get('key') or p['name']}", p["name"].strip(), p.get("relation"), p.get("birthday"), p.get("zone"), p.get("last_at"))):
            n["people"] += 1


def memory(d, n):
    for m in d.get("memories") or []:
        if m.get("path") and _new("memory", "INSERT OR IGNORE INTO zeus_memory(path, content, updated) VALUES(?, ?, ?)", (m["path"], m.get("content"), m.get("updated_at"))):
            n["memories"] += 1
    for c in d.get("cards") or []:
        if _new("memory", "INSERT OR IGNORE INTO zeus_cards(at, title, body) VALUES(?, ?, ?)", (c.get("at"), c.get("title"), c.get("body"))):
            n["cards"] += 1
    for f in d.get("feedback") or []:
        if _new("memory", "INSERT OR IGNORE INTO zeus_feedback(line_id, up, at) VALUES(?, ?, ?)", (str(f.get("line_id")), f.get("up"), f.get("at"))):
            n["feedback"] += 1
    for e in d.get("engagement") or []:
        if _new("memory", "INSERT OR IGNORE INTO zeus_engagement(key, how, taps, dismissals) VALUES(?, ?, ?, ?)", (e.get("key"), e.get("how"), e.get("taps"), e.get("dismissals"))):
            n["engagement"] += 1
    for x in d.get("notes") or []:
        if _new("memory", "INSERT OR IGNORE INTO zeus_notes(at, app, person, kind, summary) VALUES(?, ?, ?, ?, ?)", (x.get("at"), x.get("app"), x.get("person"), x.get("kind"), x.get("summary"))):
            n["notes"] += 1
    if d.get("habits") is not None and _new("memory", "INSERT OR IGNORE INTO zeus_prefs(name, json) VALUES('habits', ?)", (json.dumps(d["habits"]),)):
        n["habits"] += 1


def sleep(d, n):
    for x in d.get("nights") or []:
        if x.get("date") and _new("sleep", "INSERT OR IGNORE INTO nights(date, bed_at, asleep_at, wake_at, asleep_min, source, note) VALUES(?, ?, ?, ?, ?, ?, 'from Zeus')",
                                  (x["date"], x.get("bedAt"), x.get("asleepAt"), x.get("wakeAt"), x.get("asleepMin"), x.get("source"))):
            n["nights"] += 1


def muse(d, n):
    for s in d.get("skips") or []:
        if s.get("uri") and _new("muse", "INSERT OR IGNORE INTO skips(uri, name, artist, plays, skips, last_seen) VALUES(?, ?, ?, ?, ?, ?)",
                                 (s["uri"], s.get("name"), s.get("artist"), s.get("plays"), s.get("skips"), s.get("lastSeen"))):
            n["skips"] += 1


def chats(store, d, n):
    done = set(store.setting("zeus_chats_imported") or [])
    for c in d.get("chats") or []:
        cid = str(c.get("id"))
        lines = [x for x in c.get("lines") or [] if (x.get("text") or "").strip()]
        if cid in done or not lines:
            continue
        day = time.strftime("%b %d, %Y", time.localtime((c.get("started_at") or lines[0].get("at") or 0) / 1000))
        t = store.thread(f"Zeus chat, {day}", kind="zeus", agent="athena")
        for x in lines:
            # Nothing is addressed to an agent, so no one is woken by the old lines.
            store.post(t, "owner" if x.get("kind") in ("user", "owner", "me") else "athena", x["text"][:20000])
        # The old times, so the thread sits where it belongs in his list instead of on top.
        for (mid,), x in zip(store.db.execute("SELECT id FROM messages WHERE thread = ? ORDER BY id", (t,)).fetchall(), lines):
            store.x("UPDATE messages SET at = ? WHERE id = ?", ((x.get("at") or 0) / 1000, mid))
        first, last = (lines[0].get("at") or 0) / 1000, (lines[-1].get("at") or 0) / 1000
        store.x("UPDATE threads SET created = ?, updated = ? WHERE id = ?", (first, last, t))
        done.add(cid)
        n["chats"] += 1
    store.set_setting("zeus_chats_imported", sorted(done))


# Zeus's trip status -> the trips app's; a dismissed idea isn't a trip.
TRIP_STATUS = {"IDEA": "planning", "PLANNED": "booked", "DONE": "done", "ARCHIVED": "done"}


def _iso(ms):
    """Zeus kept epoch ms: the legs get UTC times (tz UTC), the zone each was local to isn't known."""
    return time.strftime("%Y-%m-%dT%H:%M", time.gmtime(ms / 1000)) if ms else None


def trips(d, n):
    for t in d.get("trips") or []:
        status = TRIP_STATUS.get(str(t.get("status") or "IDEA").upper())
        slug = f"zeus-{t.get('id')}"
        if not t.get("id") or not status or apps.sql("trips", "SELECT 1 FROM trips WHERE slug = ?", (slug,)):
            continue
        if status == "booked" and (t.get("endDate") or "9") < time.strftime("%Y-%m-%d"):
            status = "done"
        place = str(t.get("place") or "").strip() or None
        legs = []
        for g in t.get("legs") or []:
            mode = str(g.get("mode") or "FLIGHT").lower()
            name = " ".join(x for x in (g.get("carrier"), g.get("number")) if x) or f"{g.get('from') or '?'} to {g.get('to') or '?'}"
            detail = "; ".join(x for x in ("the way back" if g.get("returning") else "", f"platform {g['platform']}" if g.get("platform") else "") if x)
            legs.append(("car" if mode == "drive" else mode, name, _iso(g.get("departAt")), _iso(g.get("arriveAt")), g.get("from"), g.get("to"), detail or None))
        for st in t.get("stays") or []:
            legs.append(("stay", st.get("name") or "Stay", _iso(st.get("checkIn")), _iso(st.get("checkOut")), None, None, st.get("address") or None))
        for p in t.get("plans") or []:
            legs.append(("event", p.get("title") or "Plan", _iso(p.get("at")), _iso(p.get("end")), None, p.get("place") or None, None))
        # The note first (rewriting it is harmless), then the trip and its legs in one transaction: a trip row is only ever
        # there whole, so a retry after an interrupted import never skips a trip that lost its legs.
        notes = [str(x).strip() for x in t.get("notes") or [] if str(x).strip()]
        if notes:
            apps.write("trips", f"notes/trips/{slug}.md", f"# {place or 'A trip'} (from Zeus)\n\n" + "\n".join(f"- {x}" for x in notes) + "\n",
                       "his notes from Zeus's trip", "apollo")
        con = sqlite3.connect(apps._dir("trips") / "data.db")
        try:
            with con:
                trip = con.execute("INSERT INTO trips(name, slug, place, start, end, status, updated) VALUES(?, ?, ?, ?, ?, ?, ?)",
                                   (f"Trip to {place}" if place else "A trip", slug, place, t.get("startDate"), t.get("endDate"), status,
                                    int(time.time() * 1000))).lastrowid
                con.executemany("INSERT INTO legs(trip, kind, title, start_at, end_at, tz, from_place, to_place, detail, source) "
                                "VALUES(?, ?, ?, ?, ?, 'UTC', ?, ?, ?, 'zeus')", [(trip, *g) for g in legs])
        finally:
            con.close()
        apps._bump()
        n["legs"] += len(legs)
        n["zeus_trips"] += 1


PARTS = [("drives", drives), ("money", money), ("tasks", tasks), ("people", people), ("memory", memory), ("sleep", sleep), ("muse", muse), ("trips", trips)]


def run(store, body: bytes) -> dict:
    d = json.loads(body)
    if not isinstance(d, dict) or d.get("version") != 1:
        raise ValueError("not a Zeus export (version 1)")
    sha = hashlib.sha256(body).hexdigest()
    keep = config.DATA / "import"
    keep.mkdir(parents=True, exist_ok=True)
    (keep / f"zeus-data-{sha[:12]}.json").write_bytes(body)
    prepare()
    n = {k: 0 for k in ("trips", "places", "stays", "car", "odometer", "fills", "transactions", "subscriptions", "tasks", "people", "memories",
                        "cards", "feedback", "engagement", "notes", "habits", "nights", "skips", "chats", "zeus_trips", "legs")}
    skipped = []
    for app, fn in PARTS:
        if _has(app):
            fn(d, n)
        else:
            skipped.append(app)     # archived: its part waits in DATA/import
    chats(store, d, n)
    if re.fullmatch(r"[A-Za-z0-9_-]{6,80}", str(d.get("memory_store_id") or "")):
        (config.DATA / "zeus-memory-store.id").write_text(d["memory_store_id"])    # setup_brain.py reuses it
        n["memory_store_id"] = 1
    apps._bump()
    out = {"added": {k: v for k, v in n.items() if v}, "skipped_apps": skipped, "sha": sha[:12], "at": int(time.time() * 1000)}
    store.set_setting("zeus_import", out)
    store.log("apollo", f"zeus data imported: {json.dumps(out['added'])[:300]}")
    return out
