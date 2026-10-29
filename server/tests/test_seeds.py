"""Every seed folder: app.json parses, schema.sql runs on a fresh SQLite, and what app.json names exists."""
import json
import re
import sqlite3
from pathlib import Path

import pytest

SEEDS = sorted(p for p in (Path(__file__).resolve().parents[1] / "seed").iterdir() if (p / "app.json").is_file())


@pytest.mark.parametrize("seed", SEEDS, ids=lambda p: p.name)
def test_seed_folder_is_whole(seed):
    m = json.loads((seed / "app.json").read_text(encoding="utf-8"))
    assert re.match(r"^[a-z][a-z0-9-]{1,39}$", seed.name) and m["title"] and m["about"]
    for rel in m.get("pages", []) + m.get("widgets", []):
        assert (seed / rel).is_file(), rel
    for r in m.get("routines", []):
        assert r["name"] and r["action"] and (r.get("when_time") or r.get("when_trigger"))
    tables = set()
    if (seed / "schema.sql").is_file():
        con = sqlite3.connect(":memory:")
        con.executescript((seed / "schema.sql").read_text(encoding="utf-8"))
        tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
    assert set(m.get("phone_tables", [])) <= tables
