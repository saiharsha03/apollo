"""The apps whose tables the phone fills (facts -> rows), and the seed mini apps in server/seed/<name>/. Planted once;
after that they are Athena's to reshape, except their phone tables, which only the ingester writes."""
import json
import logging
import re
import shutil
import sqlite3
import time
from pathlib import Path

from . import apps

SEED = Path(__file__).resolve().parents[1] / "seed"

CORE = {
    "drives": ("Drives", "car", "Every drive the car's Bluetooth started: route points, miles, minutes, the events the phone felt (hard braking, rapid acceleration, sharp turns, speeding) and a score. Places, commute and the car are yours to work out (the tomtom tool names places and gives live-traffic routes).",
               {"trips": "CREATE TABLE IF NOT EXISTS trips (id INTEGER PRIMARY KEY, start INTEGER, end INTEGER, miles REAL, minutes REAL, points TEXT, "
                         "events TEXT, max_mph REAL, score INTEGER)",
                "stays": "CREATE TABLE IF NOT EXISTS stays (id INTEGER PRIMARY KEY, start INTEGER, end INTEGER, lat REAL, lng REAL, place TEXT)"}),
    "routines": ("Routines", "repeat", "His routines (time or trigger rows), his calls and app use, and the habits you see in them.",
                 {"calls": "CREATE TABLE IF NOT EXISTS calls (id INTEGER PRIMARY KEY, at INTEGER, who TEXT, how TEXT, missed INTEGER, seconds INTEGER)",
                  "app_usage": "CREATE TABLE IF NOT EXISTS app_usage (id INTEGER PRIMARY KEY, pkg TEXT, app TEXT, start INTEGER, end INTEGER)",
                  "unlocks": "CREATE TABLE IF NOT EXISTS unlocks (id INTEGER PRIMARY KEY, at INTEGER)"}),
    "sleep": ("Sleep", "moon", "His nights from the phone's idle signals; you write the nights and the look-backs.",
              {"signals": "CREATE TABLE IF NOT EXISTS signals (id INTEGER PRIMARY KEY, at INTEGER, kind TEXT, data TEXT)"}),
    "health": ("Health", "heart", "Health Connect's days and walks.",
               {"days": "CREATE TABLE IF NOT EXISTS days (date TEXT PRIMARY KEY, data TEXT)",
                "walks": "CREATE TABLE IF NOT EXISTS walks (id TEXT PRIMARY KEY, start INTEGER, end INTEGER, kind TEXT, data TEXT)"}),
}
ROUTINES = ("CREATE TABLE IF NOT EXISTS routines (id INTEGER PRIMARY KEY, name TEXT NOT NULL, when_time TEXT, tz TEXT, repeat TEXT, "
            "when_trigger TEXT, action TEXT NOT NULL, on_off INTEGER NOT NULL DEFAULT 1, last_run INTEGER, why TEXT, once INTEGER NOT NULL DEFAULT 0)")
# His saved places, yours to fill (from repeated stays): the phone watches each as a geofence and sends place arrive/leave.
# Trip columns added after drives was first planted (2026-10-27): the phone's events, top speed and the score.
TRIP_COLUMNS = {"events": "TEXT", "max_mph": "REAL", "score": "INTEGER"}
# Zeus's points per event (odyssey/Drive.kt); unknown kinds cost nothing.
WEIGHTS = {"hard_brake": 6, "rapid_accel": 4, "sharp_turn": 4, "speeding": 5, "phone_handling": 3}
PLACES = "CREATE TABLE IF NOT EXISTS places (id INTEGER PRIMARY KEY, name TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, radius_m REAL DEFAULT 150, kind TEXT)"


def _taken(name: str) -> bool:
    return name in {a["name"] for a in apps.list_apps()} or (apps.root() / "_archive" / name).exists()


def plant():
    for name, (title, icon, about, tables) in CORE.items():
        if name in {a["name"] for a in apps.list_apps()}:
            _add_tables(name, tables)
            continue
        if (apps.root() / "_archive" / name).exists():
            continue
        apps.create(name, title, icon, about, True, "planted by Apollo", "apollo", phone_tables=list(tables))
        for ddl in tables.values():
            apps.sql(name, ddl, phone=True)
        if name == "routines":
            apps.sql(name, ROUTINES, phone=True)
        if name == "drives":
            apps.sql(name, PLACES, phone=True)
        _core_seed(name)
    plant_seeds()


def score(events: list, miles) -> int:
    """Zeus's drive score: 100 minus the events' points per 10 km (a short drive counts as 10 km), 0 to 100. Pure."""
    penalty = sum(WEIGHTS.get(str(e.get("kind")), 0) for e in events if isinstance(e, dict))
    return max(0, min(100, round(100 - penalty / max(1.0, (miles or 0) * 1.609344 / 10))))


def _core_seed(name):
    """A core app's seed pages and README (server/seed/<name>/), copied in when it is first planted."""
    s = SEED / name
    if not (s / "app.json").is_file():
        return
    d = apps.root() / name
    for f in s.rglob("*"):
        rel = f.relative_to(s)
        if f.is_file() and rel.as_posix() != "app.json":
            (d / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(f, d / rel)
    m = json.loads((s / "app.json").read_text(encoding="utf-8"))
    with apps.LOCK:
        apps._write(d / "app.json", json.dumps(apps.manifest(name) | {"pages": m.get("pages", []), "widgets": m.get("widgets", [])}, indent=2))


def _add_tables(name, tables):
    """Phone tables added after an app was planted: made, and listed as the phone's."""
    m = apps.manifest(name)
    for t in [t for t in tables if t not in m.get("phone_tables", [])]:
        apps.sql(name, tables[t], phone=True)
    if name == "drives":
        apps.sql(name, PLACES, phone=True)
        have = {r["name"] for r in apps.sql(name, "PRAGMA table_info(trips)")}
        for col, kind in TRIP_COLUMNS.items():
            if col not in have:
                apps.sql(name, f"ALTER TABLE trips ADD COLUMN {col} {kind}", phone=True)
    apps.add_phone_tables(name, list(tables))


def plant_seeds(src: Path = SEED):
    """Each server/seed/<name>/ (app.json, schema.sql, README.md, pages, notes, widgets...) copied in as a new app, once.
    app.json's `routines` become rows in the routines table. An app that exists or was archived is never touched."""
    for s in sorted(p for p in src.iterdir() if (p / "app.json").is_file()) if src.is_dir() else []:
        if not apps.NAME.match(s.name) or _taken(s.name):
            continue
        m = json.loads((s / "app.json").read_text(encoding="utf-8"))
        apps.create(s.name, m.get("title", s.name), m.get("icon", ""), m.get("about", ""), m.get("section", True),
                    "planted by Apollo", "apollo", phone_tables=m.get("phone_tables", []))
        d = apps.root() / s.name
        try:
            for f in s.rglob("*"):
                rel = f.relative_to(s)
                if f.is_file() and rel.as_posix() not in ("app.json", "schema.sql"):
                    (d / rel).parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(f, d / rel)
            if (s / "schema.sql").is_file():
                con = sqlite3.connect(d / "data.db")
                try:
                    con.executescript((s / "schema.sql").read_text(encoding="utf-8"))
                finally:
                    con.close()
            # pages, widgets and any other keys (dynamic...) as the seed says; name, phone tables and dates as create made them
            with apps.LOCK:
                mine = apps.manifest(s.name)
                keep = {k: mine[k] for k in ("name", "phone_tables", "created", "changed")}
                apps._write(d / "app.json", json.dumps(mine | {k: v for k, v in m.items() if k != "routines"} | keep, indent=2))
        except Exception:
            shutil.rmtree(d, ignore_errors=True)    # half a seed is worse than none: it tries again next start
            apps._index()
            logging.getLogger("apollo").exception("seed %s not planted", s.name)
            continue
        for r in m.get("routines", []):
            try:
                apps.sql("routines", "INSERT INTO routines(name, when_time, tz, repeat, when_trigger, action, why) VALUES(?, ?, ?, ?, ?, ?, ?)",
                         (r["name"], r.get("when_time"), r.get("tz"), r.get("repeat"), r.get("when_trigger"), r["action"], f"planted with {s.name}"),
                         f"planted with {s.name}", "apollo")
            except apps.AppError:
                pass    # routines archived: the README still tells her the row it wants
        apps._bump()


def ingest(kind: str, at: int, data: dict):
    """A fact into its app's phone table. Unknown kinds stay in the facts table only."""
    try:
        if kind == "trip":
            # A phone from before the events (no `events` key) gets no score, not a made-up 100.
            ev = data.get("events") if isinstance(data.get("events"), list) else None
            apps.sql("drives", "INSERT INTO trips(start, end, miles, minutes, points, events, max_mph, score) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                     (data.get("start"), data.get("end"), data.get("miles"), data.get("minutes"), json.dumps(data.get("points", [])),
                      None if ev is None else json.dumps(ev), data.get("max_mph"), None if ev is None else score(ev, data.get("miles"))), phone=True)
        elif kind == "home":
            # House.snapshot: the whole device list each time, so the table is replaced. A snapshot taken while Google
            # Home isn't connected (no devices) leaves the last good list in place.
            devs = data.get("devices")
            if isinstance(devs, list) and (devs or str(data.get("link", "")).lower() == "connected"):
                apps.sql("home", "DELETE FROM devices", phone=True)
                for d in devs:
                    on = d.get("on", d.get("on_off"))
                    apps.sql("home", "INSERT OR REPLACE INTO devices(id, name, kind, room, online, on_off, level, kelvin, temp_c, controllable, at) "
                             "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                             (str(d.get("id", "")), d.get("name"), d.get("kind"), d.get("room"), 1 if d.get("online", True) else 0,
                              None if on is None else (1 if on else 0), d.get("level"), d.get("kelvin"), d.get("temp_c"),
                              1 if d.get("controllable") else 0, at), phone=True)
        elif kind == "music":
            # Muse posts each new Spotify track (title, artist, uri, playing); how long he listened isn't known yet.
            apps.sql("muse", "INSERT INTO plays(at, track, artist, album, uri, seconds, source) VALUES(?, ?, ?, ?, ?, ?, ?)",
                     (data.get("at") or at, data.get("track") or data.get("title"), data.get("artist"), data.get("album"), data.get("uri"),
                      data.get("seconds"), data.get("source") or "spotify"), phone=True)
        elif kind == "call":
            # The call's own time (the log's date), and how with its direction: "phone outgoing", "whatsapp video missed".
            apps.sql("routines", "INSERT INTO calls(at, who, how, missed, seconds) VALUES(?, ?, ?, ?, ?)",
                     (data.get("at") or at, data.get("who"), " ".join(str(x) for x in (data.get("how"), data.get("dir")) if x),
                      1 if data.get("missed") else 0, data.get("seconds")), phone=True)
        elif kind == "app_usage":
            apps.sql("routines", "INSERT INTO app_usage(pkg, app, start, end) VALUES(?, ?, ?, ?)",
                     (data.get("pkg"), data.get("app"), data.get("start"), data.get("end")), phone=True)
        elif kind == "unlock":
            # data.at is the unlock itself (UsageStats' timestamp); the fact's at is when the batch was sent
            apps.sql("routines", "INSERT INTO unlocks(at) VALUES(?)", (int(data.get("at") or at),), phone=True)
        elif kind == "sleep":
            apps.sql("sleep", "INSERT INTO signals(at, kind, data) VALUES(?, ?, ?)", (at, data.get("kind", "sleep"), json.dumps(data)), phone=True)
        elif kind == "page_write":
            # His tap on a mini-app page (a Calliope entry, a fuel fill): applied to that app's own tables, through the same
            # guard as Athena's writes (no phone tables, no triggers, no attach).
            # Only adding or changing rows: a page can't delete, drop or alter anything (Athena can, with Undo; a page can't).
            stmt = str(data.get("sql", ""))
            if re.match(r"^\s*(insert|update|replace)\b", stmt, re.I):
                apps.sql(str(data.get("app", "")), stmt, data.get("params") or [], "his tap on a page", "owner")
        elif kind == "setup":
            # Dawn's checklist: what Apollo sees of its own setup (a permission granted, a key in place, paired).
            apps.sql("dawn", "INSERT OR REPLACE INTO status(key, ok, detail, at) VALUES(?, ?, ?, ?)",
                     (str(data.get("key", ""))[:40], 1 if data.get("ok") else 0, data.get("detail"), at), phone=True)
        elif kind == "stay" and data.get("end"):
            apps.sql("drives", "INSERT INTO stays(start, end, lat, lng, place) VALUES(?, ?, ?, ?, ?)",
                     (data.get("start"), data.get("end"), data.get("lat"), data.get("lng"), data.get("place")), phone=True)
        elif kind == "place_pin":
            # His "Pin where I am": a saved place by his name; the same name again moves it.
            name, lat, lng = str(data.get("name") or "").strip()[:80], data.get("lat"), data.get("lng")
            if name and isinstance(lat, (int, float)) and isinstance(lng, (int, float)) and abs(lat) <= 90 and abs(lng) <= 180:
                if apps.sql("drives", "SELECT 1 FROM places WHERE name = ?", (name,)):
                    apps.sql("drives", "UPDATE places SET lat = ?, lng = ? WHERE name = ?", (lat, lng, name), phone=True)
                else:
                    apps.sql("drives", "INSERT INTO places(name, lat, lng, kind) VALUES(?, ?, ?, 'pinned')", (name, lat, lng), phone=True)
        elif kind == "health_walk":
            apps.sql("health", "INSERT OR REPLACE INTO walks(id, start, end, kind, data) VALUES(?, ?, ?, ?, ?)",
                     (str(data.get("id") or data.get("start")), data.get("start"), data.get("end"), data.get("kind"), json.dumps(data)), phone=True)
        elif kind in ("calendar", "birthday"):
            from . import calfacts
            calfacts.ingest(kind, at, data)
        elif kind == "health_day":
            apps.sql("health", "INSERT OR REPLACE INTO days(date, data) VALUES(?, ?)", (data.get("date") or time.strftime("%Y-%m-%d"), json.dumps(data)), phone=True)
    except apps.AppError:
        pass    # the app was archived: the fact stays in the facts table
