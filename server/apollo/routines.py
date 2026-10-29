"""The routines table's clock (routines app, docs/PLAN.md): every minute, rows whose time has come go to Athena in the
routines thread; trigger rows go when a matching fact arrives. Apollo decides nothing else: she acts on the row.

A time row: when_time "HH:MM" in its tz (default America/New_York), repeat daily | weekdays | weekends | mon,wed,... |
once:YYYY-MM-DD. A trigger row: when_trigger = a fact kind, optionally kind:word or kind:word:word (every word must
match): car:connected|disconnected, sleep:asleep|awake, alarm:ringing|snoozed|dismissed|missed, wifi:joined|left[:ssid], charging:plugged|unplugged,
place:arrive|leave[:name], nfc:<tag>, walk:started|ended, weather_alert:<severity> (extreme, severe...). Any of the
fact's own values also counts as a word (place:kohls, wifi:homenet).
Any row with once = 1 turns itself off after it runs (one-shot: "next time I get to Kohl's", "at 6 tomorrow").
"""
import datetime as dt
import threading
import time
from zoneinfo import ZoneInfo

from . import apps

DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


def due(row: dict, now: dt.datetime) -> bool:
    """True when this time row should fire in the minute `now` (aware, UTC) falls in."""
    if not row.get("on_off") or not row.get("when_time"):
        return False
    tz = zone(row)
    if tz is None:
        return False
    local = now.astimezone(tz)
    try:
        hh, mm = (int(x) for x in row["when_time"].split(":")[:2])
    except ValueError:
        return False
    if (local.hour, local.minute) != (hh, mm):
        return False
    rep = (row.get("repeat") or "daily").lower()
    day = DAYS[local.weekday()]
    if rep.startswith("once:"):
        return local.date().isoformat() == rep[5:]
    if rep == "daily":
        return True
    if rep == "weekdays":
        return local.weekday() < 5
    if rep == "weekends":
        return local.weekday() >= 5
    return day in {d.strip()[:3] for d in rep.split(",")}


def zone(row: dict):
    """The row's time zone, or None when it names no real one (that row never fires; the others carry on)."""
    try:
        return ZoneInfo(row.get("tz") or "America/New_York")
    except (ValueError, KeyError):      # ZoneInfoNotFoundError is a KeyError; a malformed key is a ValueError
        return None


def words(kind: str, data: dict) -> set:
    """What a trigger can name for this fact: its state, said the same way for every kind, plus its own values."""
    out = {str(x).lower() for x in data.values() if not isinstance(x, (dict, list))}
    if kind == "car" and "connected" in data:
        out.add("connected" if data["connected"] else "disconnected")
    elif kind == "sleep":
        # The phone says state (asleep / awake, with slept_at and woke_at); older facts only had the times.
        out.add(str(data.get("state") or ("awake" if data.get("awake_at") or data.get("wake_at") else "asleep")).lower())
    elif kind == "place":
        out.add(str(data.get("event", "")).lower())
    return out


def matches(row: dict, kind: str, data: dict) -> bool:
    trig = (row.get("when_trigger") or "").lower().strip()
    if not row.get("on_off") or not trig:
        return False
    k, *want = [w.strip() for w in trig.split(":")]
    return k == kind and set(w for w in want if w) <= words(kind, data or {})


def migrate():
    """Older routines tables get the once column."""
    try:
        if "once" not in {c["name"] for c in apps.sql("routines", "PRAGMA table_info(routines)")}:
            apps.sql("routines", "ALTER TABLE routines ADD COLUMN once INTEGER NOT NULL DEFAULT 0", phone=True)
    except apps.AppError:
        pass    # no routines app (archived)


class Clock:
    def __init__(self, store, brain):
        self.store, self.brain = store, brain
        migrate()
        threading.Thread(target=self._loop, daemon=True, name="routines").start()

    def _rows(self):
        try:
            return apps.sql("routines", "SELECT * FROM routines")
        except apps.AppError:
            return []

    def _thread(self):
        rows = self.store.q("SELECT id FROM threads WHERE kind = 'routines'")
        return rows[0]["id"] if rows else self.store.thread("Routines", "routines")

    def fire(self, row: dict, because: str):
        apps.sql("routines", "UPDATE routines SET last_run = ?, on_off = ? WHERE id = ?",
                 (int(time.time() * 1000), 0 if row.get("once") else row.get("on_off", 1), row["id"]), "routine ran", "apollo")
        t = self._thread()
        self.store.post(t, "apollo", f"ROUTINE DUE ({because}): #{row['id']} {row.get('name')}: {row.get('action')}" + (" (one-shot: now off)" if row.get("once") else ""), "athena")
        self.brain.enqueue(t)

    def on_fact(self, kind: str, data: dict):
        for r in self._rows():
            try:
                if matches(r, kind, data):
                    self.fire(r, f"trigger {r['when_trigger']}")
            except Exception as e:
                self.store.set_status("routines", False, f"routine #{r.get('id')}: {type(e).__name__}")

    def _loop(self):
        while True:
            time.sleep(60 - time.time() % 60 + 1)
            try:
                if self.tick(dt.datetime.now(dt.timezone.utc)):
                    self.store.set_status("routines", True, "ok")      # a clean minute: a routines alert clears
            except Exception as e:
                self.store.set_status("routines", False, type(e).__name__)

    def tick(self, now: dt.datetime) -> bool:
        """True when every row was fine."""
        ok = True
        for r in self._rows():
            try:
                if r.get("when_time") and zone(r) is None:
                    ok = False
                    self.store.set_status("routines", False, f"routine #{r.get('id')}: no time zone called {r.get('tz')!r}")
                elif due(r, now) and (r.get("last_run") or 0) < (time.time() - 90) * 1000:
                    self.fire(r, f"{r['when_time']} {r.get('tz') or 'America/New_York'}")
            except Exception as e:     # one bad row never stops the clock
                ok = False
                self.store.set_status("routines", False, f"routine #{r.get('id')}: {type(e).__name__}")
        return ok
