"""TomTom for the agents (Owner's key, handed over from Calypso slot "tomtom"): live-traffic routes, what's near a point,
the street at a point, and a place search. Only api.tomtom.com is called, over https; only the coordinates (and a search's
words) go. The key never reaches a log, an error or a reply.
"""
import datetime as dt
import json
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo

from . import config
from .tools import when_ms

HOST = "https://api.tomtom.com"
TZ = ZoneInfo("America/New_York")

# TomTom traffic sections: TEC main cause codes and simple categories worth naming (Zeus's Commute.kt).
CAUSE = {1: "Heavy traffic", 2: "Crash", 3: "Road work", 4: "Lane closure", 5: "Road closed", 6: "Slippery road",
         9: "Hazard", 10: "Object on the road", 13: "Broken-down vehicle", 19: "Weather"}
CATEGORY = {"JAM": "Heavy traffic", "ROAD_WORK": "Road work", "ROAD_CLOSURE": "Road closed"}


class Refused(Exception):
    """A call not made (no key, over the day's cap) or one TomTom failed; the text is safe to show."""


def point(v, name):
    """'lat,lng' (or [lat, lng]) -> (lat, lng), checked."""
    parts = v if isinstance(v, (list, tuple)) else str(v).split(",")
    if len(parts) != 2:
        raise ValueError(f"{name} must be 'lat,lng'")
    lat, lng = float(parts[0]), float(parts[1])
    if not (-90 <= lat <= 90 and -180 <= lng <= 180):
        raise ValueError(f"{name} is not a coordinate")
    return lat, lng


def _p(lat, lng):
    return f"{lat:.5f},{lng:.5f}"


def _iso(ms):
    return dt.datetime.fromtimestamp(ms / 1000, TZ).isoformat(timespec="seconds")


def key(path=None, label="TomTom"):
    try:
        k = (path or config.TOMTOM_KEY).read_text().strip()
    except OSError:
        k = ""
    if not k:
        raise Refused(f"no {label} key on the server yet (Pair screen > Send key)")
    return k


def count(store, name="tomtom", label="TomTom"):
    """One more call today, or Refused once the day's cap (setting <name>_daily_cap) is reached."""
    day = dt.datetime.now(TZ).date().isoformat()
    c = store.setting(f"{name}_calls") or {}
    n = c.get("n", 0) if c.get("day") == day else 0
    if n >= int(store.setting(f"{name}_daily_cap")):
        raise Refused(f"daily {label} limit")
    store.set_setting(f"{name}_calls", {"day": day, "n": n + 1})


def fetch(url, k, label="TomTom"):
    """GET JSON over https in 10 s. Any failure becomes Refused with the key cut out (the text may carry the URL)."""
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            return json.loads(r.read())
    except Exception as e:
        raise Refused(f"{label} failed ({type(e).__name__}: {redact(str(e), k)[:200]})") from None


def redact(text, k):
    """text with the key cut out, plain or URL-quoted."""
    for s in (k, urllib.parse.quote(k), urllib.parse.quote_plus(k)):
        text = text.replace(s, "[key]")
    return text


def get(store, path, params):
    k = key()
    count(store)
    return fetch(HOST + path + "?" + urllib.parse.urlencode({**params, "key": k}), k)


def run(store, a):
    kind = a["kind"]
    if kind == "route":
        return route(store, a)
    if kind == "nearby":
        return nearby(store, a)
    if kind == "reverse":
        return reverse(store, a)
    if kind == "search":
        return search(store, a)
    raise ValueError("kind is route, nearby, reverse or search")


def route(store, a):
    f, t = point(a["from"], "from"), point(a["to"], "to")
    q = {"traffic": "true" if a.get("traffic", True) else "false", "travelMode": "car", "routeType": "fastest",
         "maxAlternatives": max(0, min(int(a.get("alternatives", 1)), 5)), "sectionType": "traffic", "computeTravelTimeFor": "all"}
    if a.get("arrive_at") not in (None, ""):
        q["arriveAt"] = _iso(when_ms(a["arrive_at"]))
    elif a.get("depart_at") not in (None, ""):
        q["departAt"] = _iso(when_ms(a["depart_at"]))
    j = get(store, f"/routing/1/calculateRoute/{_p(*f)}:{_p(*t)}/json", q)
    routes = j.get("routes") or []
    if not routes:
        raise Refused("TomTom found no route")
    out = summary(routes[0])
    out["alternatives"] = [summary(r)["minutes"] for r in routes[1:]]
    if a.get("points"):
        out["points"] = [[round(p["latitude"], 5), round(p["longitude"], 5)] for leg in routes[0].get("legs", []) for p in leg.get("points", [])]
    return out


def summary(r):
    s = r.get("summary", {})
    return {"minutes": round(s.get("travelTimeInSeconds", 0) / 60, 1), "delay_minutes": round(s.get("trafficDelayInSeconds", 0) / 60, 1),
            "no_traffic_minutes": round(s.get("noTrafficTravelTimeInSeconds", s.get("travelTimeInSeconds", 0)) / 60, 1),
            "miles": round(s.get("lengthInMeters", 0) / 1609.344, 1), "depart": s.get("departureTime"), "arrive": s.get("arrivalTime"),
            "incidents": incidents(r)}


def incidents(r):
    out = []
    for sec in r.get("sections", []):
        if sec.get("sectionType") != "TRAFFIC":
            continue
        delay = sec.get("delayInSeconds", 0)
        causes = (sec.get("tec") or {}).get("causes") or [{}]
        cause = causes[0].get("mainCauseCode")
        word = CAUSE.get(cause) or CATEGORY.get(sec.get("simpleCategory"))
        if not word or (delay < 60 and cause not in (2, 5)):
            continue
        line = word + (f", +{round(delay / 60)} min" if delay >= 60 else "")
        if line not in out:
            out.append(line)
    return out


def _limit(a, default):
    return max(1, min(int(a.get("limit", default)), 20))


def place(r):
    poi, addr, pos = r.get("poi") or {}, r.get("address") or {}, r.get("position") or {}
    brand = ((poi.get("brands") or [{}])[0]).get("name")
    return {"name": brand or poi.get("name") or addr.get("freeformAddress"), "category": ", ".join(poi.get("categories") or []) or r.get("type"),
            "address": addr.get("freeformAddress"), "lat": pos.get("lat"), "lng": pos.get("lon"),
            "meters": round(r["dist"]) if "dist" in r else None}


def nearby(store, a):
    lat, lng = point(a["at"], "at")
    radius = max(10, min(int(a.get("radius_m", 300)), 50000))
    j = get(store, "/search/2/nearbySearch/.json", {"lat": f"{lat:.5f}", "lon": f"{lng:.5f}", "radius": radius, "limit": _limit(a, 10)})
    return sorted((place(r) for r in j.get("results", [])), key=lambda p: p["meters"] if p["meters"] is not None else 1e9)


def reverse(store, a):
    lat, lng = point(a["at"], "at")
    j = get(store, f"/search/2/reverseGeocode/{_p(lat, lng)}.json", {})
    addrs = j.get("addresses") or []
    if not addrs:
        return "No address there."
    ad = addrs[0].get("address", {})
    return {"address": ad.get("freeformAddress"), "street": ad.get("streetName"), "number": ad.get("streetNumber"),
            "city": ad.get("municipality"), "state": ad.get("countrySubdivision"), "postcode": ad.get("postalCode")}


def search(store, a):
    words = str(a["query"]).strip()
    if not words:
        raise ValueError("query is empty")
    q = {"limit": _limit(a, 5)}
    if a.get("at") not in (None, ""):
        lat, lng = point(a["at"], "at")
        q.update(lat=f"{lat:.5f}", lon=f"{lng:.5f}")
        if a.get("radius_m"):
            q["radius"] = max(10, min(int(a["radius_m"]), 50000))
    j = get(store, f"/search/2/search/{urllib.parse.quote(words[:200], safe='')}.json", q)
    return [place(r) for r in j.get("results", [])]

