"""birthday_add, Athena's one narrow birthday tool (she spots the wishes in notifications; Apollo only saves).

It writes only the people app: a new person, or an EMPTY birthday filled (never one already set), with where it came
from in notes; the people README's routine row; and asks the phone for a real Google birthday on his contact
(add_birthday, his tap by default). Each save gets a Home card "Saved <name>'s birthday" with Undo and no expiry. His
Undo (a card_feedback fact) takes back exactly what it did, once: the person it made or the birthday it filled, the
routine row, and the contact birthday (remove_birthday; a failure shows on the card). A person it made that changed
since is kept, with only the birthday and its note taken off. The saves wait in the setting
birthday_saves, by person id.
"""
import datetime as dt
import re
import unicodedata

from . import apps
from .store import inquote

CARD = "birthday:"
DATE = re.compile(r"(?:(\d{4})-)?(\d{2})-(\d{2})")


def check_name(name: str) -> str:
    """A person's name as it comes from other people's wishes: up to 5 words of letters (any script) and . ' - only, up to
    60. The name lands in a routine Athena later runs with full access, so nothing that reads like an instruction gets in."""
    name = " ".join(str(name or "").split())
    if not name or len(name) > 60 or len(name.split()) > 5 or not all(c.isalnum() or c in " .'-" or unicodedata.category(c).startswith("M") for c in name):
        raise ValueError("name must be up to 5 words of letters and . ' - only (up to 60 characters)")
    return name


def check_date(date: str) -> str:
    """MM-DD or YYYY-MM-DD, a real day (Feb 29 without a year is fine); ValueError otherwise."""
    m = DATE.fullmatch(str(date or "").strip())
    if not m:
        raise ValueError("date must be MM-DD or YYYY-MM-DD")
    y = int(m.group(1)) if m.group(1) else 2000
    try:
        dt.date(y, int(m.group(2)), int(m.group(3)))
    except ValueError:
        raise ValueError(f"{date} isn't a real day") from None
    if m.group(1) and not 1900 <= y <= dt.date.today().year:
        raise ValueError(f"{y} isn't a birth year")
    return m.group(0)


def next_date(mmdd: str, after: dt.date) -> dt.date:
    """The next MM-DD after `after`. Feb 29 falls on Feb 28 in other years."""
    m, d = int(mmdd[:2]), int(mmdd[3:5])
    for y in (after.year, after.year + 1):
        try:
            day = dt.date(y, m, d)
        except ValueError:
            day = dt.date(y, 2, 28)
        if day > after:
            return day
    return dt.date(after.year + 1, m, min(d, 28))


def add(store, who: str, name: str, date: str, source: str, pid=None, tz=None, facts_turn=False) -> str:
    """facts_turn: called from her facts turn. The action and card say so: their why and title carry the source (other
    people's text), so the phone's answer and his taps on the card go back to the facts turn, never his taps thread."""
    name, source = check_name(name), str(source or "").strip()[:100]
    date = check_date(date)
    rows = apps.sql("people", "SELECT * FROM people WHERE id = ?", (int(pid),)) if pid not in (None, "") else \
        apps.sql("people", "SELECT * FROM people WHERE lower(trim(name)) = lower(?)", (name,))
    if pid not in (None, "") and not rows:
        raise ValueError(f"no person {pid} in the people app")
    p = rows[0] if rows else None
    if p and p.get("birthday"):
        return f"Not saved: {p['name']} already has a birthday ({p['birthday']}); it stays as it is."
    note = f"birthday from {source}" if source else "birthday saved by Athena"
    why = "birthday_add" + (f": {source}" if source else "")
    if p:
        apps.sql("people", "UPDATE people SET birthday = ?, tz = COALESCE(tz, ?), notes = TRIM(COALESCE(notes || ?, '') || ?) WHERE id = ?",
                 (date, tz or None, "; ", note, p["id"]), why, who)
        pid, name, created, tz = p["id"], p["name"], False, p.get("tz") or tz
    else:
        pid = apps.sql("people", "INSERT INTO people(name, birthday, tz, notes) VALUES(?, ?, ?, ?)", (name, date, tz or None, note), why, who, rowid=True)
        created = True
    row = apps.sql("people", "SELECT * FROM people WHERE id = ?", (pid,))[0] if created else None     # Undo deletes it only unchanged
    mmdd = date[-5:]
    zone = tz or "America/New_York"
    rid = apps.sql("routines", "INSERT INTO routines(name, when_time, tz, repeat, action, why) VALUES(?, '00:00', ?, ?, ?, ?)",
                   (f"Birthday: {name}", zone, f"once:{next_date(mmdd, dt.date.today())}",
                    f"A card to wish people #{pid} ({inquote(name)}) happy birthday, with a WhatsApp button. Then set repeat to next year's date.",
                    inquote(note)), why, who, rowid=True)
    mark = {"facts_turn": True} if facts_turn else {}
    aid = store.send("action", {"action": "add_birthday", "args": {"name": name, "date": date}, "why": f"{name}'s birthday ({note})", "from": who, **mark})
    with store.lock:
        saves = store.setting("birthday_saves") or {}
        saves[str(pid)] = {"name": name, "date": date, "created": created, "routine": rid, "action": aid, "note": note, "row": row}
        store.set_setting("birthday_saves", saves)
    store.send("card", {"id": f"{CARD}{pid}", "title": f"Saved {name}'s birthday: {_day(date)}", "body": (f"From {source}." if source else "Saved by Athena.")
                        + " It goes on your Google contact once you tap Yes.", "actions": ["Undo"], "app": "people", "from": "apollo", **mark})
    return (f"Saved {name}'s birthday ({date}) in the people app" + (" as a new person" if created else "")
            + f", with its routine row; the phone asks him to put it on his Google contact (action {aid}). A Home card shows it with Undo.")


def _day(date: str) -> str:
    d = dt.date(2000, int(date[-5:-3]), int(date[-2:]))
    return f"{d:%B} {d.day}" + (f", {date[:4]}" if len(date) == 10 else "")


def undo(store, card: str) -> bool:
    """His Undo on a birthday card: what birthday_add did is taken back, once. False when the card isn't one of these."""
    if not card.startswith(CARD):
        return False
    pid = card[len(CARD):]
    with store.lock:
        saves = store.setting("birthday_saves") or {}
        s = saves.pop(pid, None)
        if s is not None:
            store.set_setting("birthday_saves", saves)
    if s is None:
        return True
    try:
        now = apps.sql("people", "SELECT * FROM people WHERE id = ?", (int(pid),))
        if s["created"] and now and now[0] == s.get("row"):
            apps.sql("people", "DELETE FROM people WHERE id = ? AND birthday = ?", (int(pid), s["date"]), "his Undo", "owner")
        else:       # a person it filled, or one it made that he or Athena changed since: only the birthday and its note go
            apps.sql("people", "UPDATE people SET birthday = NULL, notes = NULLIF(TRIM(REPLACE(REPLACE(REPLACE(notes, ?, ''), ?, ''), ?, '')), '') "
                     "WHERE id = ? AND birthday = ?", ("; " + s["note"], s["note"] + "; ", s["note"], int(pid), s["date"]), "his Undo", "owner")
        apps.sql("routines", "DELETE FROM routines WHERE id = ? AND name = ?", (s["routine"], f"Birthday: {s['name']}"), "his Undo", "owner")
    except apps.AppError:
        pass    # the app was archived: nothing left there to take back
    store.send("action", {"action": "cancel", "args": {"id": str(s["action"])}, "why": "his Undo", "from": "apollo"})
    rid = store.send("action", {"action": "remove_birthday", "args": {"name": s["name"], "date": s["date"]}, "why": "his Undo", "from": "apollo"})
    with store.lock:
        waiting = store.setting("birthday_undos") or {}
        waiting[str(rid)] = {"pid": pid, "name": s["name"]}
        store.set_setting("birthday_undos", waiting)
    store.send("card_remove", {"id": card, "from": "apollo"})
    return True


def action_result(store, oid, ok: bool, detail: str):
    """The phone's answer to an Undo's remove_birthday: a failure goes back on the card. A wait for his tap isn't an answer."""
    if not ok and detail.lower().startswith("waiting"):
        return
    with store.lock:
        waiting = store.setting("birthday_undos") or {}
        u = waiting.pop(str(oid), None)
        if u is None:
            return
        store.set_setting("birthday_undos", waiting)
    if not ok:
        store.send("card", {"id": f"{CARD}{u['pid']}", "title": f"Undid {u['name']}'s birthday in Apollo",
                            "body": f"The phone couldn't take it off your Google contact: {detail[:200]}. Remove it in Contacts.", "actions": [],
                            "app": "people", "from": "apollo"})
