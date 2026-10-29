"""Drives: the trip fact's events, top speed and score; old tables gain the columns; the seed page replaces Athena's."""
import json
import sqlite3
from pathlib import Path

from test_server import ROOT, tools  # noqa: F401  (env and fixtures)

from apollo import apps, config, migrate, seed

HERE = Path(__file__).resolve().parent


def test_score_is_zeus_formula():
    assert seed.score([], 3) == 100
    assert seed.score([{"kind": "hard_brake"}, {"kind": "speeding"}, {"kind": "nope"}, "x"], 2) == 89    # short: counts as 10 km
    assert seed.score([{"kind": "hard_brake"}] * 4, 12.4) == 88                                             # 24 points over 2 x 10 km
    assert seed.score([{"kind": "hard_brake"}] * 30, 1) == 0


def test_trip_fact_keeps_events_top_speed_and_score():
    seed.plant()
    ev = [{"kind": "hard_brake", "t": 5, "lat": 42.4, "lng": -71.0, "value": 0.41}]
    seed.ingest("trip", 1, {"start": 77001, "end": 77002, "miles": 2.0, "minutes": 9, "points": [[42.4, -71.0, 1, 12.5]],
                            "events": ev, "max_mph": 44.3})
    seed.ingest("trip", 1, {"start": 77003, "end": 77004, "miles": 2.0, "minutes": 9, "points": [[42.4, -71.0, 1]]})
    a, b = apps.sql("drives", "SELECT events, max_mph, score FROM trips WHERE start IN (77001, 77003) ORDER BY start")
    assert json.loads(a["events"]) == ev and a["max_mph"] == 44.3 and a["score"] == 94
    assert b == {"events": None, "max_mph": None, "score": None}    # an old phone: no events, no made-up score


def test_an_old_trips_table_gains_the_columns():
    seed.plant()
    con = sqlite3.connect(apps.root() / "drives" / "data.db")
    con.executescript("DROP TABLE trips; CREATE TABLE trips (id INTEGER PRIMARY KEY, start INTEGER, end INTEGER, miles REAL, minutes REAL, points TEXT);"
                      "INSERT INTO trips(start, miles) VALUES(1, 3.0);")
    con.close()
    seed.plant()
    seed.plant()    # twice: no second ALTER
    assert {"events", "max_mph", "score"} <= {r["name"] for r in apps.sql("drives", "PRAGMA table_info(trips)")}
    assert apps.sql("drives", "SELECT miles, score FROM trips WHERE start = 1") == [{"miles": 3.0, "score": None}]


def test_athenas_drives_page_gives_way_to_the_seed_only_while_unchanged():
    seed.plant()
    d = apps.root() / "drives"
    page = d / "pages" / "index.html"
    (d / "README.md").unlink(missing_ok=True)
    page.write_bytes((HERE / "drives_athena_page.html").read_bytes())
    assert migrate.run() == ["drives/pages/index.html"]
    assert page.read_bytes() == (seed.SEED / "drives" / "pages" / "index.html").read_bytes() and (d / "README.md").is_file()
    assert apps.manifest("drives")["pages"] == ["pages/index.html"] and migrate.run() == []
    page.write_bytes((HERE / "drives_athena_page.html").read_bytes() + b"<!-- hers -->")
    assert migrate.run() == []


def test_a_fresh_drives_app_gets_the_seed_page(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "APPS", tmp_path)
    seed.plant()
    assert apps.manifest("drives")["pages"] == ["pages/index.html"]
    assert (tmp_path / "drives" / "pages" / "index.html").read_bytes() == (seed.SEED / "drives" / "pages" / "index.html").read_bytes()
    assert (tmp_path / "drives" / "README.md").is_file() and (tmp_path / "drives" / "pages" / "fonts" / "figtree.ttf").is_file()
    assert {"events", "max_mph", "score"} <= {r["name"] for r in apps.sql("drives", "PRAGMA table_info(trips)")}
