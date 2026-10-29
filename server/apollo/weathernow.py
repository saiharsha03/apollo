"""The sky on Home: every 30 minutes the weather where he is (his last known location, else Boston) comes from
Open-Meteo and goes to the weather app's now.json, which the phone mirrors and ui/Sky.kt reads:
{"kind": clear | cloudy | rain | snow, "temp": °F, "temp_c": °C, "summary", "place": a saved place, the town or "his last location", "at"}. Only rounded coordinates (~1 km) go out.
"""
import json
import threading
import time
import urllib.request

from . import apps, nws

URL = ("https://api.open-meteo.com/v1/forecast?latitude={lat:.2f}&longitude={lng:.2f}"
       "&current=temperature_2m,weather_code&temperature_unit=fahrenheit")
EVERY_S = 30 * 60
# WMO weather codes (Open-Meteo's docs): the words for the summary, and the sky's kind.
CODES = {0: "Clear", 1: "Mostly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog", 48: "Fog", 51: "Light drizzle",
         53: "Drizzle", 55: "Heavy drizzle", 56: "Freezing drizzle", 57: "Freezing drizzle", 61: "Light rain", 63: "Rain",
         65: "Heavy rain", 66: "Freezing rain", 67: "Freezing rain", 71: "Light snow", 73: "Snow", 75: "Heavy snow",
         77: "Snow grains", 80: "Showers", 81: "Showers", 82: "Heavy showers", 85: "Snow showers", 86: "Snow showers",
         95: "Thunderstorm", 96: "Thunderstorm, hail", 99: "Thunderstorm, hail"}


def kind(code) -> str:
    if code in (71, 73, 75, 77, 85, 86):
        return "snow"
    if code is not None and code >= 51:
        return "rain"
    return "cloudy" if code in (2, 3, 45, 48) else "clear"


def fetch(lat, lng):
    with urllib.request.urlopen(URL.format(lat=lat, lng=lng), timeout=20) as r:
        return json.loads(r.read().decode())


def place_name(store, spot, reverse=None) -> str:
    """A name a page can show: one of his saved places within ~300 m, else the town from TomTom (remembered per ~1 km,
    so it isn't asked every half hour), else the spot's own name."""
    lat, lng = spot["lat"], spot["lng"]
    try:
        for p in apps.sql("drives", "SELECT name, lat, lng FROM places WHERE lat IS NOT NULL AND lng IS NOT NULL"):
            if abs(p["lat"] - lat) < 0.003 and abs(p["lng"] - lng) < 0.004:
                return str(p["name"]).strip("«»")
    except apps.AppError:
        pass
    key = f"{lat:.2f},{lng:.2f}"
    seen = store.setting("weather_place") or {}
    if seen.get("at") == key:
        return seen["name"]
    try:
        from . import tomtom
        ad = (reverse or tomtom.reverse)(store, {"at": key})      # the ~1 km rounded point only, never his exact spot
        name = ", ".join(x for x in (ad.get("city"), ad.get("state")) if x) if isinstance(ad, dict) else ""
    except Exception:       # no key, a cap, the network: the spot's own name
        name = ""
    name = name or spot["name"]
    store.set_setting("weather_place", {"at": key, "name": name})
    return name


def update(store, get=fetch) -> bool:
    """One reading into weather/now.json. True when the file changed (the app version is bumped so the phone pulls it)."""
    spot = nws.last_spot(store) or nws.BOSTON
    cur = (get(spot["lat"], spot["lng"]) or {}).get("current") or {}
    code = cur.get("weather_code")
    f = cur.get("temperature_2m")
    c = round((f - 32) * 5 / 9, 1) if isinstance(f, (int, float)) else None
    now = {"kind": kind(code), "temp": f, "temp_c": c, "summary": CODES.get(code, ""), "place": place_name(store, spot)}
    d = apps.root() / "weather"
    if not (d / "app.json").is_file():
        return False        # the weather app is archived
    p = d / "now.json"
    with apps.LOCK:
        try:
            old = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            old = {}
        if {k: old.get(k) for k in now} == now:
            return False
        apps._write(p, json.dumps(dict(now, at=int(time.time() * 1000))))
        apps._bump()
    return True


def start(store):
    """A reading now, then every 30 minutes."""
    def loop():
        while True:
            try:
                update(store)
                store.set_status("weather_now", True, "updated")
            except Exception as e:
                store.set_status("weather_now", False, type(e).__name__)
            time.sleep(EVERY_S)
    threading.Thread(target=loop, daemon=True, name="weather-now").start()
