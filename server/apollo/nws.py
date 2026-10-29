"""NWS severe-weather alerts for his places (Zeus's Aeolia background check, now on the server). Every 30 minutes the
active alerts at each saved place (the drives app's `places` table) come from api.weather.gov; each new one is a
`weather_alert` fact for Athena, who decides what pings him. Extreme and Severe ones are marked urgent. An Update of an
alert already told is not told again. Only the place's coordinates go to NWS. With no saved places, his last known
location and Boston are checked.
"""
import json
import threading
import time
import urllib.request

from . import apps

URL = "https://api.weather.gov/alerts/active?point={lat:.4f},{lng:.4f}"
UA = "apollo-server (personal assistant; weather alerts for one person)"   # NWS asks every client for a User-Agent
EVERY_S = 30 * 60
KEEP = 300      # alert ids remembered: protects the settings row; alerts expire within days
BOSTON = {"name": "Boston", "lat": 42.386, "lng": -71.078}


def covered(lat, lng):
    """NWS covers the US and its territories in the west; a place in India is never asked."""
    return 13 <= lat <= 72 and (-180 <= lng <= -64 or 144 <= lng <= 146)


def last_spot(store):
    """Where the phone last put him: the newest stay's coordinates or a trip's last point, or None."""
    for r in store.q("SELECT kind, data FROM facts WHERE kind IN ('stay', 'trip') ORDER BY id DESC LIMIT 20"):
        d = json.loads(r["data"])
        p = (d.get("points") or [[None, None]])[-1][:2] if r["kind"] == "trip" else [d.get("lat"), d.get("lng")]
        if all(isinstance(x, (int, float)) for x in p) and len(p) == 2:
            return {"name": "his last location", "lat": p[0], "lng": p[1]}
    return None


def places(store=None):
    """His saved places NWS covers, one per ~10 m (two rows for one spot are asked once). None saved: his last known
    location and Boston."""
    try:
        rows = apps.sql("drives", "SELECT name, lat, lng FROM places WHERE lat IS NOT NULL AND lng IS NOT NULL")
    except apps.AppError:
        rows = []
    if not rows and store is not None:
        rows = [p for p in (last_spot(store), BOSTON) if p]
    seen, out = set(), []
    for r in rows:
        k = (round(r["lat"], 4), round(r["lng"], 4))
        if k not in seen and covered(r["lat"], r["lng"]):
            seen.add(k)
            out.append(r)
    return out


def fetch(lat, lng):
    req = urllib.request.Request(URL.format(lat=lat, lng=lng), headers={"User-Agent": UA, "Accept": "application/geo+json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())


def parse(j):
    """NWS GeoJSON to the fields Athena reads."""
    out = []
    for f in (j or {}).get("features", []):
        p = f.get("properties") or {}
        if not p.get("id"):
            continue
        out.append({"id": p["id"], "event": p.get("event"), "severity": p.get("severity"), "urgency": p.get("urgency"),
                    "certainty": p.get("certainty"), "headline": p.get("headline"), "area": p.get("areaDesc"),
                    "onset": p.get("onset"), "ends": p.get("ends") or p.get("expires"), "message_type": p.get("messageType"),
                    "description": (p.get("description") or "")[:1200], "instruction": (p.get("instruction") or "")[:600],
                    "references": [r.get("identifier") for r in p.get("references") or [] if r.get("identifier")]})
    return out


def new(alerts, told):
    """Alerts not yet told: neither the id nor any alert it updates."""
    return [a for a in alerts if a["id"] not in told and not any(r in told for r in a["references"])]


def check(store, on_fact=None, get=fetch):
    """One pass over his places. Returns how many facts it made."""
    told = list(store.setting("nws_told") or [])
    made, failed, here = 0, [], places(store)
    for pl in here:
        try:
            alerts = parse(get(pl["lat"], pl["lng"]))
        except Exception as e:
            failed.append(f"{pl['name']}: {type(e).__name__}")
            continue
        for a in new(alerts, set(told)):
            data = dict(a, place=pl["name"])
            store.add_fact("weather_alert", int(time.time() * 1000), data, a["severity"] in ("Extreme", "Severe"))
            if on_fact:
                on_fact("weather_alert", data)
            made += 1
        # Updates of a told alert are told too, so the next update is caught by its own id.
        told += [a["id"] for a in alerts if a["id"] not in told]
    store.set_setting("nws_told", told[-KEEP:])
    if here:
        store.set_status("nws", not failed, "; ".join(failed)[:300] or f"checked {len(here)} places")
    return made


def start(store, brain, on_fact):
    """The 30-minute loop (the first pass 30 minutes after start); on_fact lets routine rows trigger on weather_alert."""
    def loop():
        while True:
            time.sleep(EVERY_S)
            try:
                if check(store, on_fact):
                    brain.facts_now()
            except Exception as e:
                store.set_status("nws", False, type(e).__name__)
    threading.Thread(target=loop, daemon=True, name="nws").start()
