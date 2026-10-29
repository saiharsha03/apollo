"""Server alerts (SSH and Jellyfin logins, Pan's blocked egress, media bot) into the security mini app.

The services that send them to Telegram also drop a copy into SPOOL with apollo-mirror (installed as
/usr/local/bin/claw-mirror, the path they already call). Every 20 s this moves each drop into the security app's
`events` table and deletes the file. Only files owned by root or the agents' user count (the spool is writable by
anyone, sticky); the text is someone's log line, so it stays data: it never wakes Athena or reaches an agent.
"""
import json
import math
import os
import threading
import time
from pathlib import Path

from . import apps, calypso

SPOOL = Path(os.environ.get("APOLLO_ALERTS_SPOOL", "/var/spool/apollo-alerts"))
LIMIT = 8192


def _owner(uid: int):
    if uid == 0:
        return "root"
    return "agents" if calypso.origin(uid) == "agents" else None


def intake(spool: Path = SPOOL) -> int:
    """Moves the drops into the app, oldest first. How many were added."""
    if not spool.is_dir() or not (apps.root() / "security" / "app.json").is_file():
        return 0
    n = 0
    for f in sorted(spool.glob("*.json")):
        try:
            st, raw = calypso.read_drop(f, LIMIT)
            who = _owner(st.st_uid)
            d = json.loads(raw.decode("utf-8"))
            if who and isinstance(d, dict) and str(d.get("text") or "").strip():
                ts = d.get("ts")
                at = ts if isinstance(ts, (int, float)) and math.isfinite(ts) and 0 < ts < 1e11 else st.st_mtime
                source = str(d.get("source") or "alert")[:24]
                if who == "agents" and source != "media":
                    source = "agents"       # only root's services may say security or plazmi
                apps.sql("security", "INSERT INTO events(at, source, text, origin) VALUES(?, ?, ?, ?)",
                         (int(at * 1000), source, str(d["text"])[:2000], who), phone=True)
                n += 1
        except Exception:       # noqa: BLE001 - one bad drop never blocks the rest
            pass
        finally:
            try:
                f.unlink()
            except OSError:
                pass
    return n


def start(store):
    def loop():
        while True:
            try:
                intake()
                store.set_status("security", True, "read")
            except Exception as e:      # noqa: BLE001 - a bad read is a status, not a dead thread
                store.set_status("security", False, type(e).__name__)
            time.sleep(20)
    threading.Thread(target=loop, daemon=True, name="securitylog").start()
