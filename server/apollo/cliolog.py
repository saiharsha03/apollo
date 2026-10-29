"""Clio on the server (Zeus's gateway/clio.py): the phone uploads its new log lines once a day, kept here a file per UTC
day under DATA/clio for 30 days, so Athena can read them with `phone_logs` when something broke. The phone already drops
Calypso's lines and anything key-shaped; this checks the fields again, drops the same areas, and dedupes by (at, area,
event), so a retried upload adds nothing. Log lines are kinds and outcomes, never his words.
"""
import datetime as dt
import json
import re
import threading
import time

from . import config

MAX_BYTES = 1024 * 1024
MAX_LINES = 2000
DAY_MAX = 20 * 1024 * 1024
KEEP_DAYS = 30
LEVELS = {"info", "warn", "error"}
SEALED = {"calypso", "vault", "keys", "seal", "janus", "plazmi"}   # the phone's ClioShip.SEALED, the same set
AREA = re.compile(r"[a-z0-9_.-]{1,32}")
NAME = re.compile(r"(\d{4}-\d{2}-\d{2})\.jsonl")
_lock = threading.Lock()


def root():
    d = config.DATA / "clio"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _day(ms):
    return dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc).date().isoformat()


def clean(lines) -> list[dict]:
    """Apollo's lines ({at, area, level, event, detail}) and Zeus's old ones ({ts, ..., level INFO}) in one shape."""
    if not isinstance(lines, list) or len(lines) > MAX_LINES:
        raise ValueError(f"a list of at most {MAX_LINES} lines")
    out, seen = [], set()
    for x in lines:
        if not isinstance(x, dict):
            continue
        at, area = x.get("at", x.get("ts")), str(x.get("area") or "").lower()
        if not isinstance(at, int) or isinstance(at, bool) or not 0 < at < 10**14 or not AREA.fullmatch(area) or area in SEALED:
            continue
        level = str(x.get("level") or "info").lower()
        line = {"at": at, "area": area, "level": level if level in LEVELS else "info",
                "event": str(x.get("event") or "")[:120], "detail": str(x.get("detail") or "")[:2000]}
        k = (at, area, line["event"])
        if k not in seen:
            seen.add(k)
            out.append(line)
    return out


def put(lines, now=None) -> dict:
    lines = clean(lines)
    stored = dropped = 0
    with _lock:
        by_day = {}
        for ln in lines:
            by_day.setdefault(_day(ln["at"]), []).append(ln)
        for day, batch in sorted(by_day.items()):
            p = root() / f"{day}.jsonl"
            have, size = set(), 0
            if p.exists():
                size = p.stat().st_size
                for s in p.read_text(encoding="utf-8", errors="replace").splitlines():
                    try:
                        o = json.loads(s)
                        have.add((o.get("at"), o.get("area"), o.get("event")))
                    except ValueError:
                        pass
            text = []
            for ln in batch:
                s = json.dumps(ln, ensure_ascii=False) + "\n"
                if (ln["at"], ln["area"], ln["event"]) in have:
                    continue
                if size + len(s.encode()) > DAY_MAX:
                    dropped += 1
                    continue
                have.add((ln["at"], ln["area"], ln["event"]))
                text.append(s)
                size += len(s.encode())
            if text:
                with open(p, "a", encoding="utf-8") as f:
                    f.write("".join(text))
                stored += len(text)
        pruned = prune(now or time.time())
    return {"stored": stored, "dropped": dropped, "pruned": pruned}


def prune(now) -> int:
    oldest = (dt.datetime.fromtimestamp(now, dt.timezone.utc).date() - dt.timedelta(days=KEEP_DAYS - 1)).isoformat()
    n = 0
    for p in root().glob("*.jsonl"):
        m = NAME.fullmatch(p.name)
        if m and m.group(1) < oldest:
            p.unlink(missing_ok=True)
            n += 1
    return n


def read(area="", level="", contains="", since_hours=24.0, limit=200) -> list[dict]:
    """Newest first. level 'warn' means warn and error; contains matches event or detail."""
    since = (time.time() - float(since_hours) * 3600) * 1000
    want = {"error": {"error"}, "warn": {"warn", "error"}}.get(level, LEVELS)
    limit = max(1, min(int(limit), 1000))
    out = []
    for p in sorted(root().glob("*.jsonl"), reverse=True):
        m = NAME.fullmatch(p.name)
        if not m or m.group(1) < _day(since):
            continue
        for s in reversed(p.read_text(encoding="utf-8", errors="replace").splitlines()):
            try:
                o = json.loads(s)
            except ValueError:
                continue
            if o.get("at", 0) < since or o.get("level") not in want or (area and o.get("area") != area):
                continue
            if contains and contains.lower() not in (o.get("event", "") + " " + o.get("detail", "")).lower():
                continue
            out.append(o)
            if len(out) >= limit:
                return out
    return out
