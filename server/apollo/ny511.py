"""511NY road events (crashes, closures, road work on NY roads) for the agents, with Owner's key (Calypso slot "ny511").
The request carries only the key: the whole state's list comes back and is filtered here to a corridor or a point, so
no location leaves the server. The list is kept 5 minutes (511NY allows 10 calls a minute).
"""
import math
import re
import time
import urllib.parse

from . import config
from .tomtom import count, fetch, key, point

URL = "https://511ny.org/api/getevents"
KEEP_S = 300
_cache = {"at": 0.0, "events": []}

KINDS = ("accident", "incident", "closure", "roadwork", "construction")
NEAR = re.compile(r"(?i)\b(?:at|near)\s+(exit\s*\d+\w?|[A-Z][\w.' ]{2,30}?)(?=\s*[;,.(]|$)")


def events(store):
    """The state's events (Zeus's fields), from the 5-minute copy when it is fresh."""
    if time.time() - _cache["at"] < KEEP_S:
        return _cache["events"]
    k = key(config.NY511_KEY, "511NY")
    count(store, "ny511", "511NY")
    j = fetch(URL + "?" + urllib.parse.urlencode({"key": k, "format": "json"}), k, "511NY")
    rows = j if isinstance(j, list) else (j or {}).get("events", [])
    out = []
    for e in rows:
        try:
            lat, lng = float(e["Latitude"]), float(e["Longitude"])
        except (KeyError, TypeError, ValueError):
            continue
        out.append({"type": e.get("EventType") or "", "road": e.get("RoadwayName") or "", "direction": e.get("DirectionOfTravel") or "",
                    "description": e.get("Description") or "", "severity": e.get("Severity") or "", "lat": lat, "lng": lng})
    _cache.update(at=time.time(), events=out)
    return out


def meters(lat1, lng1, lat2, lng2):
    x = math.radians(lng2 - lng1) * math.cos(math.radians((lat1 + lat2) / 2))
    return 6371000 * math.hypot(x, math.radians(lat2 - lat1))


def to_segment(lat, lng, a, b):
    """Metres from a point to the straight line a-b (flat near the segment; fine at commute scale)."""
    k = math.cos(math.radians(lat))
    px, py = (lng - a[1]) * k, lat - a[0]
    bx, by = (b[1] - a[1]) * k, b[0] - a[0]
    t = max(0.0, min(1.0, (px * bx + py * by) / (bx * bx + by * by))) if bx or by else 0.0
    return 111195 * math.hypot(px - t * bx, py - t * by)


def word(e):
    if "accident" in e["type"].lower() or re.search(r"(?i)crash|accident", e["description"]):
        return "Crash"
    return "Closure" if "closure" in e["type"].lower() else "Road work"


def line(e):
    """'Crash on I-190 N near exit 9' (Zeus's Ny511.line)."""
    d = e["direction"]
    d = f" {d[0].upper()}" if d and d.lower() not in ("unknown", "all directions") else ""
    m = NEAR.search(e["description"])
    near = ""
    if m:
        near = m.group(1).strip()
        near = " near " + re.sub(r"(?i)^exit", "exit", near[0].lower() + near[1:])
    return f"{word(e)} on {e['road']}{d}{near}".strip()


def run(store, a):
    if a.get("from") not in (None, "") or a.get("to") not in (None, ""):
        f, t = point(a["from"], "from"), point(a["to"], "to")
        corridor = max(100, min(int(a.get("corridor_m", 1500)), 20000))
        dist = lambda e: to_segment(e["lat"], e["lng"], f, t)
    elif a.get("at") not in (None, ""):
        p = point(a["at"], "at")
        corridor = max(100, min(int(a.get("radius_m", 5000)), 100000))
        dist = lambda e: meters(p[0], p[1], e["lat"], e["lng"])
    else:
        raise ValueError("give from and to (a corridor) or at (a point)")
    limit = max(1, min(int(a.get("limit", 5)), 20))
    hits = [(e, dist(e)) for e in events(store) if any(k in e["type"].lower() for k in KINDS)]
    hits = [h for h in hits if h[1] < corridor]
    order = {"Crash": 0, "Closure": 1, "Road work": 2}
    hits.sort(key=lambda h: (not h[0]["severity"].lower().startswith("major"), order[word(h[0])], h[1]))
    out = []
    for e, _ in hits:
        if line(e) not in out:
            out.append(line(e))
    return out[:limit] or "No 511NY events there."

