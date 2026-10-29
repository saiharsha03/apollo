"""Iris's and Atlas's plan windows onto the Usage page. Apollo's own collector (deploy/apollo-plans, apollo-plans.timer)
writes claude.json (Iris's Claude Code windows) and codex.json (Atlas's Codex windows) into PLANS every 5 minutes; this
reads them and adds a usage row per window when it changed. Shape: {"provider", "plan", "windows": [{"key", "label",
"used_pct", "resets_at"}], "fetched", "ok", "note"}."""
import json
import threading
import time
from pathlib import Path

from . import apps

PLANS = Path("/var/lib/apollo-plans")
WHO = {"claude": "iris", "codex": "atlas"}


def meter(provider: str, w: dict) -> str | None:
    """The usage page's meter for one window. Pure."""
    label = str(w.get("label") or "").lower()
    if provider == "claude":
        return {"5h": "claude_5h", "7d": "claude_7d"}.get(label)
    if provider == "codex":
        return {"5h": "codex_5h", "7d": "codex_week"}.get(label)
    return None


def _ms(t):
    return int(t * 1000) if isinstance(t, (int, float)) and t < 1e12 else t


def read(root: Path = PLANS) -> list:
    """Rows (who, meter, used_pct, resets_at, detail) from the snapshots; a missing or broken one gives none."""
    rows = []
    for name, who in WHO.items():
        try:
            d = json.loads((root / f"{name}.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not d.get("ok"):
            continue
        for w in d.get("windows") or []:
            m = meter(name, w)
            if m and isinstance(w.get("used_pct"), (int, float)):
                rows.append((who, m, float(w["used_pct"]), _ms(w.get("resets_at")), f"{d.get('provider', name)} {d.get('plan', '')}".strip()))
    return rows


def update(root: Path = PLANS) -> int:
    """Adds a reading per window whose numbers changed since its newest row. How many were added."""
    if not (apps.root() / "usage" / "app.json").is_file():
        return 0
    added = 0
    for who, m, pct, resets, detail in read(root):
        last = apps.sql("usage", "SELECT used_pct, resets_at FROM usage WHERE who = ? AND meter = ? ORDER BY at DESC LIMIT 1", (who, m))
        if last and last[0]["used_pct"] == pct and last[0]["resets_at"] == resets:
            continue
        apps.sql("usage", "INSERT INTO usage(at, who, meter, used_pct, resets_at, detail, source) VALUES(?, ?, ?, ?, ?, ?, ?)",
                 (int(time.time() * 1000), who, m, pct, resets, detail, "plan window"), phone=True)
        added += 1
    return added


def start(store):
    def loop():
        while True:
            try:
                update()
                store.set_status("plans", True, "read")
            except Exception as e:      # noqa: BLE001 - a bad read is a status, not a dead thread
                store.set_status("plans", False, type(e).__name__)
            time.sleep(300)
    threading.Thread(target=loop, daemon=True, name="plans").start()
