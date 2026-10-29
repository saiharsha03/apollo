"""His calendar and birthdays from the phone, into the tables that show them (phone tables: only this ingester writes).

calendar fact {from, to, events: [{id, title, start, end, all_day, location, calendar}]}: the calendar app's `events`
holds what the phone last saw in that window (the calendar page overlays it with Athena's routines and hide rules).
birthday fact {name, date: MM-DD or YYYY-MM-DD, source: contacts|calendar}: the people app's `birthdays` table, for
Athena to fold into people (Zeus read them from his contacts and Google's birthdays calendar).
"""
import re
import time

from . import apps

PHONE = {
    # One row per occurrence: a repeating event's occurrences share its id (what calendar_update takes), not its start.
    "calendar": {"events": "CREATE TABLE IF NOT EXISTS events (id INTEGER NOT NULL, title TEXT, start INTEGER NOT NULL, end INTEGER, "
                           "all_day INTEGER NOT NULL DEFAULT 0, location TEXT, calendar TEXT, seen INTEGER, PRIMARY KEY (id, start))"},
    "people": {"birthdays": "CREATE TABLE IF NOT EXISTS birthdays (name TEXT PRIMARY KEY, date TEXT NOT NULL, source TEXT, at INTEGER)"},
}
DATE = re.compile(r"(\d{4}-)?\d{2}-\d{2}")


def phone_table(app, name):
    """Made when missing and listed under the app's phone_tables (so agents can't write it)."""
    if (app, name) == ("calendar", "events"):
        _occurrences()
    apps.sql(app, PHONE[app][name], phone=True)
    apps.add_phone_tables(app, [name])


def _occurrences():
    """An older events table keyed by the event id alone (each occurrence overwrote the last) becomes one keyed by id and start."""
    pk = [c["name"] for c in apps.sql("calendar", "PRAGMA table_info(events)") if c["pk"]]
    if pk != ["id"]:
        return
    apps.sql("calendar", PHONE["calendar"]["events"].replace("events (", "events_new (", 1), phone=True)
    apps.sql("calendar", "INSERT OR REPLACE INTO events_new SELECT id, title, start, end, all_day, location, calendar, seen FROM events", phone=True)
    apps.sql("calendar", "DROP TABLE events", phone=True)
    apps.sql("calendar", "ALTER TABLE events_new RENAME TO events", phone=True)


def ingest(kind, at, data):
    if kind == "calendar":
        phone_table("calendar", "events")
        lo, hi = int(data.get("from") or 0), int(data.get("to") or 0)
        if hi > lo:
            apps.sql("calendar", "DELETE FROM events WHERE start < ? AND COALESCE(end, start) >= ?", (hi, lo), phone=True)
        now = int(time.time() * 1000)
        for e in (data.get("events") or [])[:2000]:
            if e.get("id") is None or not e.get("start"):
                continue
            apps.sql("calendar", "INSERT OR REPLACE INTO events(id, title, start, end, all_day, location, calendar, seen) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                     (int(e["id"]), str(e.get("title") or "")[:300], int(e["start"]), e.get("end"), 1 if e.get("all_day") else 0,
                      str(e.get("location") or "")[:300], str(e.get("calendar") or "")[:100], now), phone=True)
    elif kind == "birthday":
        name, date = str(data.get("name") or "").strip()[:100], str(data.get("date") or "").strip()
        if name and DATE.fullmatch(date):
            phone_table("people", "birthdays")
            apps.sql("people", "INSERT OR REPLACE INTO birthdays(name, date, source, at) VALUES(?, ?, ?, ?)",
                     (name, date, str(data.get("source") or "")[:20], at), phone=True)
