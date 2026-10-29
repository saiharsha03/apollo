"""What came back from Zeus (restored.py and its modules): Spotify, Pan, chat search, Clio, owner.db, drives fixes,
Metron, the Zeus data import, calendar and birthday facts."""
import json
import sqlite3
import time
import urllib.error

import pytest
from test_server import PHONE, ROOT, tools  # noqa: F401  (env and fixtures)

from apollo import apps, seed


class Reply:
    def __init__(self, status, body):
        self.status, self.body = status, body

    def read(self):
        return json.dumps(self.body).encode() if self.body is not None else b""

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class FakeSpotify:
    def __init__(self, answers):
        self.answers, self.calls = answers, []

    def __call__(self, req, timeout=None):
        url = req.full_url
        self.calls.append((url, req.headers.get("Authorization", ""), req.data))
        for prefix, (status, body) in self.answers.items():
            if url.startswith(prefix):
                if status >= 400:
                    raise urllib.error.HTTPError(url, status, "no", {}, None)
                return Reply(status, body)
        raise AssertionError(url)


def client():
    from fastapi.testclient import TestClient
    from apollo import main
    return TestClient(main.app)


def test_spotify_handover_refresh_and_search_never_show_tokens(tools, monkeypatch):
    c = client()
    assert c.post("/v1/spotify_tokens", headers=PHONE, json={"refresh": "short"}).status_code == 400
    assert c.post("/v1/spotify_tokens", headers=PHONE, json={"access": "old-access", "refresh": "R" * 40, "expiresAt": 1, "scope": "user-top-read"}).json()["ok"]
    track = {"type": "track", "name": "Song", "uri": "spotify:track:1", "artists": [{"name": "A"}], "duration_ms": 180000,
             "album": {"name": "Al", "images": [{"url": "https://i.scdn.co/big"}, {"url": "https://i.scdn.co/mid"}]}}
    fake = FakeSpotify({"https://accounts.spotify.com/api/token": (200, {"access_token": "new-access", "expires_in": 3600}),
                        "https://api.spotify.com/v1/search": (200, {"tracks": {"items": [track]}})})
    monkeypatch.setattr("urllib.request.urlopen", fake)
    out = json.loads(tools.run("athena", "spotify", {"kind": "search", "query": "song"}))
    assert out == [{"name": "Song", "uri": "spotify:track:1", "type": "track", "by": "A", "album": "Al", "art": "https://i.scdn.co/mid", "minutes": 3.0}]
    assert b"client_id=YOUR_SPOTIFY_CLIENT_ID" in fake.calls[0][2] and fake.calls[1][1] == "Bearer new-access"
    assert {u.split("/")[2] for u, _, _ in fake.calls} <= {"accounts.spotify.com", "api.spotify.com"}
    assert not tools.store.q("SELECT * FROM log WHERE what LIKE '%access%' OR what LIKE '%RRRR%'")
    assert "Not done" in tools.run("athena", "spotify", {"kind": "top", "time_range": "forever"})


def test_spotify_refused_refresh_signs_out(tools, monkeypatch):
    from apollo import spotify
    spotify.save({"access": "", "refresh": "R" * 40, "expires_at": 0, "scope": ""})
    monkeypatch.setattr("urllib.request.urlopen", FakeSpotify({"https://accounts.spotify.com/": (400, {})}))
    assert "refused his sign-in" in tools.run("athena", "spotify", {"kind": "recent"})
    assert not spotify.path().exists()
    assert "isn't connected" in tools.run("athena", "spotify", {"kind": "recent"})


@pytest.fixture
def pan_files(monkeypatch):
    from apollo import pan
    d = ROOT / f"plazmi{time.time_ns()}"
    d.mkdir()
    for k, n in (("FEED", "feed.jsonl"), ("LIMITS", "limits.json"), ("INBOX", "inbox.jsonl"), ("MOVEIN", "movein.json")):
        monkeypatch.setattr(pan, k, d / n)
    monkeypatch.setattr(pan, "UID", None)
    return d


def test_pan_feed_copy_is_checked_and_idempotent(pan_files):
    from apollo import pan
    seed.plant()
    lines = [{"ts": 1000, "kind": "postcard", "text": "Hi‮ there <b>", "place": "Plaza"}, {"ts": 1001, "kind": "evil", "text": "x"},
             {"ts": 1002, "kind": "status", "text": "ok", "used": 5, "limit": 9}, "not json"]
    (pan_files / "feed.jsonl").write_text("\n".join(json.dumps(x) for x in lines) + "\n")
    assert pan.copy_feed() == 2 and pan.copy_feed() == 0
    assert [(r["ts"], r["kind"]) for r in pan.recent() if r["ts"] >= 1000] == [(1002, "status"), (1000, "postcard")]
    assert client().get("/v1/pan", headers=PHONE).json()["feed"][0]["ts"] >= 1002
    rows = apps.sql("plazmi", "SELECT ts, kind, text, used, token_limit FROM feed WHERE ts >= 1000 ORDER BY ts")
    assert rows == [{"ts": 1000, "kind": "postcard", "text": "Hi there <b>", "used": None, "token_limit": None},
                    {"ts": 1002, "kind": "status", "text": "ok", "used": 5, "token_limit": 9}]


def test_talk_to_pan_is_athenas_and_the_phone_sets_limits(tools, pan_files):
    assert "Said to Pan" in tools.run("athena", "talk_to_pan", {"text": "Owner says hello"})
    assert "Not done" in tools.run("iris", "talk_to_pan", {"text": "do this"})
    assert json.loads(tools.run("iris", "talk_to_pan", {}))["said"][-1] == {"at": json.loads(tools.run("iris", "talk_to_pan", {}))["said"][-1]["at"], "from": "Athena", "text": "Owner says hello"}
    c = client()
    assert c.put("/v1/pan/limits", headers=PHONE, json={"daily_tokens": -1}).status_code == 400
    assert c.put("/v1/pan/limits", headers=PHONE, json={"daily_tokens": 200000, "paused": True}).json()["paused"] is True
    assert c.get("/v1/pan", headers=PHONE).json()["limits"]["daily_tokens"] == 200000
    assert c.post("/v1/pan/say", headers=PHONE, json={"text": "hi Pan"}).json()["from"] == "Owner"
    assert c.post("/v1/pan/confirm", headers=PHONE, json={"phrase": "two words"}).status_code == 400
    assert c.put("/v1/pan/key", headers=PHONE, json={"key": "nope"}).status_code == 400


def test_search_chats_only_finds_what_the_agent_can_see(tools):
    t1, t2, g = tools.store.thread("mine", agent="iris"), tools.store.thread("hers", agent="athena"), tools.store.thread("all", agent="group")
    tools.store.post(t1, "owner", "the lease paper is in the drawer", "iris")
    tools.store.post(t2, "owner", "the lease is secret from iris", "athena")
    tools.store.post(g, "owner", "lease talk for everyone")
    found = json.loads(tools.run("iris", "search_chats", {"query": "lease"}))
    assert {r["thread"] for r in found} == {t1, g}
    assert {r["thread"] for r in json.loads(tools.run("athena", "search_chats", {"query": "LEASE"}))} >= {t2, g}
    assert "No messages" in tools.run("iris", "search_chats", {"query": "100%_"})


def test_clio_upload_drops_sealed_dedupes_and_phone_logs_filters(tools):
    c = client()
    now = int(time.time() * 1000)
    lines = [{"at": now, "area": "hermes", "level": "info", "event": "torch", "detail": "ran"},
             {"ts": now - 1000, "area": "odyssey", "level": "ERROR", "event": "drive", "detail": "gps lost"},
             {"at": now, "area": "calypso", "level": "error", "event": "open", "detail": "x"}]
    assert c.post("/v1/clio", headers=PHONE, json=lines).json()["stored"] == 2
    assert c.post("/v1/clio", headers=PHONE, json=lines).json()["stored"] == 0
    assert c.post("/v1/clio", headers=PHONE, json={"not": "a list"}).status_code == 400
    errs = json.loads(tools.run("athena", "phone_logs", {"level": "warn"}))
    assert [e["area"] for e in errs] == ["odyssey"] and errs[0]["level"] == "error"
    assert "calypso" not in tools.run("athena", "phone_logs", {"since_hours": 1})


def test_iris_db_copy_drops_secrets_and_runs_only_selects(tools, tmp_path, monkeypatch):
    from apollo import irisdb
    db = tmp_path / "owner.db"
    con = sqlite3.connect(db)
    con.executescript("""
        CREATE TABLE api_keys (id INTEGER, value TEXT); INSERT INTO api_keys VALUES (1, 'sk-secret');
        CREATE TABLE accounts (id INTEGER, kind TEXT, card TEXT, balance REAL, note TEXT);
        INSERT INTO accounts VALUES (1, 'balance', '4111 1111 1111 1234', 900, 'x'), (2, 'spend', '4111 1111 1111 9876', 1, 'call 7165551234567');
        CREATE TABLE tasks (id INTEGER, title TEXT, context TEXT, notes TEXT, user_token TEXT);
        INSERT INTO tasks VALUES (1, 'File the court form', 'legal', 'details', 't'), (2, 'Buy milk', 'home', 'whole', 't');""")
    con.close()
    monkeypatch.setattr(irisdb, "PATH", db)
    monkeypatch.setattr(irisdb, "_copy", {"db": None, "at": 0.0, "schema": []})
    names = {t["table"]: t for t in json.loads(tools.run("athena", "iris_db_schema", {}))}
    assert set(names) == {"accounts", "tasks"} and "balance" not in names["accounts"]["columns"] and "user_token" not in names["tasks"]["columns"]
    rows = json.loads(tools.run("athena", "query_iris_db", {"sql": "SELECT * FROM accounts"}))
    assert rows == [{"id": 2, "kind": "spend", "card": "9876", "note": "call "}]
    assert json.loads(tools.run("athena", "query_iris_db", {"sql": "SELECT title, notes FROM tasks ORDER BY id"}))[0] == {"title": "Private task", "notes": ""}
    for bad in ("SELECT * FROM api_keys", "DELETE FROM tasks", "SELECT 1; SELECT 2", "WITH x AS (SELECT 1) INSERT INTO tasks(id) SELECT 1"):
        assert tools.run("athena", "query_iris_db", {"sql": bad}).startswith("Not done")
    monkeypatch.setattr(irisdb, "_copy", {"db": None, "at": 0.0, "schema": []})
    monkeypatch.setattr(irisdb, "PATH", tmp_path / "missing" / "owner.db")
    assert "can't be read here" in tools.run("athena", "iris_db_schema", {})


def test_drives_fix_changes_phone_rows_with_undo(tools):
    seed.plant()
    seed.ingest("stay", 1, {"start": 5, "end": 9, "lat": 1.0, "lng": 2.0, "place": None})
    seed.ingest("trip", 1, {"start": 77, "end": 99, "miles": 0.1, "minutes": 1, "points": []})
    sid = apps.sql("drives", "SELECT MAX(id) AS id FROM stays")[0]["id"]
    tid = apps.sql("drives", "SELECT id FROM trips WHERE start = 77")[0]["id"]
    assert "Not done" in tools.run("athena", "drives_fix", {"kind": "stay_place", "id": sid, "place": "Gym", "why": ""})
    assert "Done (fix" in tools.run("athena", "drives_fix", {"kind": "stay_place", "id": sid, "place": "Gym", "why": "he said so"})
    assert apps.sql("drives", "SELECT place FROM stays WHERE id = ?", (sid,))[0]["place"] == "Gym"
    out = tools.run("athena", "drives_fix", {"kind": "trip_delete", "id": tid, "why": "parking lot shuffle"})
    assert not apps.sql("drives", "SELECT 1 FROM trips WHERE id = ?", (tid,))
    fix = int(out.split("fix ")[1].split(")")[0])
    assert "undone" in tools.run("athena", "drives_fix", {"kind": "undo", "fix": fix, "why": "it was real"})
    assert apps.sql("drives", "SELECT miles FROM trips WHERE id = ?", (tid,))[0]["miles"] == 0.1
    assert "Not done" in tools.run("athena", "drives_fix", {"kind": "undo", "fix": fix, "why": "again"})
    assert "Not done" in tools.run("athena", "app_sql", {"app": "drives", "sql": "DELETE FROM trips", "why": "w"})


def test_metron_alerts_once_and_holds_jobs_at_90(tools):
    from apollo import restored
    seed.plant()
    later = int(time.time() * 1000) + 3600_000
    apps.sql("usage", "DELETE FROM usage", why="test", who="apollo")
    apps.sql("usage", "INSERT INTO usage(at, who, meter, used_pct, resets_at) VALUES(?, 'iris', 'claude_5h', 91, ?)", (1, later), "t", "iris")
    restored.metron(tools.store)
    restored.metron(tools.store)
    assert [a["level"] for a in tools.store.alerts() if a["source"] == "metron"] == ["normal"]
    assert "no new jobs at 90%" in tools.run("athena", "ask_iris", {"job": "check mail"})
    assert "Sent to Iris" in tools.run("athena", "ask_atlas", {"job": "x"}).replace("Atlas", "Iris")
    apps.sql("usage", "INSERT INTO usage(at, who, meter, used_pct, resets_at) VALUES(?, 'iris', 'claude_5h', 96, ?)", (2, later), "t", "iris")
    restored.metron(tools.store)
    assert sorted(a["level"] for a in tools.store.alerts() if a["source"] == "metron") == ["high", "normal"]
    apps.sql("usage", "DELETE FROM usage", why="test", who="apollo")


def test_zeus_import_fills_the_apps_once(tools):
    from apollo import zeusimport
    seed.plant()
    d = {"version": 1,
         "drives": [{"id": 3, "start": 1_700_000_000_000, "end": 1_700_000_600_000, "meters": 16093.44, "points": [[42.4, -71.0, 1]]}],
         "places": [{"name": "Zeus Home", "lat": 42.4, "lng": -71.0, "kind": "HOME"}],
         "car": {"lease": {"car": "Sedan", "termMonths": 36, "allowance": 10000}, "fillups": [{"at": 1_700_000_000_500, "cents": 4200, "gallons": 12.0}],
                 "readings": [{"at": 1, "miles": 1200}]},
         "money": {"transactions": [{"id": 7, "at": 1_700_000_000_000, "cents": 1299, "kind": "spend", "merchant": "Wegmans", "last4": "001234"}],
                   "subscriptions": [{"name": "Netflix", "cents": 1549, "cycle": "MONTHLY"}]},
         "tasks": {"projects": [{"id": 1, "name": "Car"}], "tasks": [{"id": 9, "title": "Oil change", "list": "NEXT", "project_id": 1}, {"id": 10, "title": "x", "sample": 1}]},
         "people": [{"key": "p1", "name": "Mom", "relation": "mother", "zone": "Asia/Kolkata"}],
         "memories": [{"path": "/profile/home.md", "content": "Boston", "updated_at": 5}],
         "nights": [{"date": "2026-10-20", "asleepMin": 400}], "skips": [{"uri": "spotify:track:9", "skips": 3}],
         "chats": [{"id": 42, "started_at": 1_700_000_000_000, "lines": [{"at": 1_700_000_000_000, "kind": "user", "text": "hi"}, {"at": 1_700_000_001_000, "kind": "assistant", "text": "hello"}]}],
         "memory_store_id": "memstore_01Zeus"}
    body = json.dumps(d).encode()
    out = zeusimport.run(tools.store, body)
    assert out["added"]["trips"] == 1 and out["added"]["tasks"] == 1 and out["added"]["chats"] == 1 and out["added"]["fills"] == 1
    assert (ROOT / "data" / "zeus-memory-store.id").read_text() == "memstore_01Zeus"
    again = zeusimport.run(tools.store, body)
    assert set(again["added"]) == {"memory_store_id"}
    assert apps.sql("drives", "SELECT miles FROM trips WHERE start = 1700000000000")[0]["miles"] == 10.0
    assert apps.sql("money", "SELECT amount, last4 FROM transactions WHERE iris_id = 'zeus:7'")[0] == {"amount": 12.99, "last4": "1234"}
    assert apps.sql("tasks", "SELECT dirty, list_id IS NOT NULL AS l FROM tasks WHERE zeus_id = 'zeus:9'")[0] == {"dirty": 1, "l": 1}
    assert apps.sql("drives", "SELECT term_months FROM car")[0]["term_months"] == 36
    t = tools.store.q("SELECT id FROM threads WHERE kind = 'zeus'")[-1]["id"]
    assert [m["sender"] for m in tools.store.messages(t)] == ["owner", "athena"] and not tools.store.undelivered("athena")
    assert tools.store.q("SELECT updated FROM threads WHERE id = ?", (t,))[0]["updated"] == 1_700_000_001
    with pytest.raises(ValueError):
        zeusimport.run(tools.store, b'{"version": 2}')


def test_calendar_and_birthday_facts_land_in_phone_tables(tools):
    seed.plant()
    seed.ingest("calendar", 1, {"from": 0, "to": 10**13, "events": [{"id": 5, "title": "Dentist", "start": 100, "end": 200}]})
    seed.ingest("calendar", 1, {"from": 0, "to": 10**13, "events": [{"id": 6, "title": "Gym", "start": 300, "end": 400}]})
    assert [r["title"] for r in apps.sql("calendar", "SELECT title FROM events")] == ["Gym"]
    seed.ingest("birthday", 7, {"name": "Sam", "date": "04-12", "source": "contacts"})
    seed.ingest("birthday", 7, {"name": "Bad", "date": "April"})
    assert apps.sql("people", "SELECT name, date FROM birthdays") == [{"name": "Sam", "date": "04-12"}]
    assert "birthdays" in apps.manifest("people")["phone_tables"]
    assert "Not done" in tools.run("athena", "app_sql", {"app": "calendar", "sql": "DELETE FROM events", "why": "w"})
    assert "OK" in tools.run("athena", "app_sql", {"app": "calendar", "sql": "INSERT INTO day_types(date, type) VALUES('2026-10-27', 'office')", "why": "w"})


def test_repeating_events_keep_every_occurrence_and_old_tables_move_over():
    seed.plant()
    apps.sql("calendar", "DROP TABLE events", phone=True)
    apps.sql("calendar", "CREATE TABLE events (id INTEGER PRIMARY KEY, title TEXT, start INTEGER NOT NULL, end INTEGER, "
                         "all_day INTEGER NOT NULL DEFAULT 0, location TEXT, calendar TEXT, seen INTEGER)", phone=True)
    apps.sql("calendar", "INSERT INTO events(id, title, start) VALUES(1, 'Old', 50)", phone=True)
    weekly = [{"id": 9, "title": "Standup", "start": s, "end": s + 10} for s in (1000, 2000, 3000)]
    seed.ingest("calendar", 1, {"from": 900, "to": 4000, "events": weekly})
    rows = apps.sql("calendar", "SELECT id, title, start FROM events ORDER BY start")
    assert [(r["id"], r["start"]) for r in rows] == [(1, 50), (9, 1000), (9, 2000), (9, 3000)]


def test_phone_setting_takes_theme_and_nav_order_words(tools):
    assert "theme = default" in tools.run("athena", "phone_setting", {"name": "theme", "value": "Default", "why": "he asked"})
    assert "Not done" in tools.run("athena", "phone_setting", {"name": "theme", "value": "neon", "why": "x"})
    tools.run("athena", "phone_setting", {"name": "nav_order", "value": "chat, home,apps", "why": "he asked"})
    assert tools.store.outbox(0)[-1]["data"]["value"] == "chat,home,apps"
    seed.plant()
    tools.run("athena", "phone_setting", {"name": "nav_order", "value": "chat,App:Dawn,iris,more", "why": "he asked"})
    assert tools.store.outbox(0)[-1]["data"]["value"] == "chat,app:dawn,iris,more"
    for bad in ("chat,app:nope", "chat,app:", "chat,chat", "chat,,iris", "app:../dawn"):
        assert "Not done" in tools.run("athena", "phone_setting", {"name": "nav_order", "value": bad, "why": "x"}), bad
    assert "Not done" in tools.run("athena", "phone_setting", {"name": "reask_min", "value": "lots", "why": "x"})
    assert "night_start_h_sat = 1" in tools.run("athena", "phone_setting", {"name": "night_start_h_sat", "value": 1, "why": "x"})
    for name in ("goal_steps", "debug_mode", "night_start_h_xyz"):
        assert "Not done" in tools.run("athena", "phone_setting", {"name": name, "value": 1, "why": "x"}), name


def test_weather_widget_fix_replaces_only_the_untouched_old_seed():
    import subprocess
    from apollo import migrate
    seed.plant()
    w = apps.root() / "weather" / "widgets" / "now.html"
    old = subprocess.run(["git", "show", "c009a53:server/seed/weather/widgets/now.html"], capture_output=True).stdout
    (apps.root() / "weather" / "pages" / "fonts" / "doto.ttf").unlink(missing_ok=True)
    w.write_bytes(old.replace(b"\n", b"\r\n"))
    assert migrate.run() == ["weather/widgets/now.html"] and "°F" in w.read_text(encoding="utf-8")
    assert (apps.root() / "weather" / "pages" / "fonts" / "doto.ttf").exists() and migrate.run() == []
    w.write_bytes(old + b"<!-- Athena's -->")
    assert migrate.run() == [] and w.read_bytes().endswith(b"Athena's -->")


def test_home_fix_replaces_the_old_tap_page_and_readme_only_when_untouched():
    import subprocess
    from apollo import migrate
    seed.plant()
    d = apps.root() / "home"
    for rel in ("pages/index.html", "README.md"):
        (d / rel).write_bytes(subprocess.run(["git", "show", f"5c10373:server/seed/home/{rel}"], capture_output=True).stdout)
    assert sorted(migrate.run()) == ["home/README.md", "home/pages/index.html"]
    assert "said" in (d / "pages" / "index.html").read_text(encoding="utf-8") and migrate.run() == []
