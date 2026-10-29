"""Real costs from Anthropic's Cost API (the admin key from Calypso), the same numbers as the Console's Cost page. Read at
most once a minute (Anthropic's polling guidance) and kept in memory between reads."""
import datetime as dt
import json
import threading
import time
import urllib.parse
import urllib.request

from . import config

_lock = threading.Lock()
_cache = {"at": 0.0, "value": None}


def _get(path: str, params: list) -> dict:
    url = "https://api.anthropic.com" + path + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"anthropic-version": "2023-06-01", "x-api-key": config.ADMIN_KEY.read_text().strip(),
                                               "User-Agent": "Apollo/1.0"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read())


def _cents(bucket: dict) -> float:
    return sum(float(x.get("amount", 0) or 0) for x in bucket.get("results", []))


def report() -> dict | None:
    """{"today_usd", "month_usd", "days": [{"date", "usd"}]} for this month, or None without an admin key."""
    if not config.ADMIN_KEY.exists():
        return None
    with _lock:
        if time.time() - _cache["at"] < 60 and _cache["value"] is not None:
            return _cache["value"]
        now = dt.datetime.now(dt.timezone.utc)
        start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        days, page = [], None
        while True:
            params = [("starting_at", start.strftime("%Y-%m-%dT%H:%M:%SZ")), ("ending_at", (now + dt.timedelta(days=1)).strftime("%Y-%m-%dT00:00:00Z")),
                      ("bucket_width", "1d"), ("limit", "31")] + ([("page", page)] if page else [])
            d = _get("/v1/organizations/cost_report", params)
            days += [{"date": str(b.get("starting_at", ""))[:10], "usd": round(_cents(b) / 100, 2)} for b in d.get("data", [])]
            if not d.get("has_more"):
                break
            page = d.get("next_page")
        today = now.strftime("%Y-%m-%d")
        value = {"today_usd": next((x["usd"] for x in days if x["date"] == today), 0.0), "month_usd": round(sum(x["usd"] for x in days), 2), "days": days}
        _cache.update(at=time.time(), value=value)
        return value
