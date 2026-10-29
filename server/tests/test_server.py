import hashlib
import json
import os
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(tempfile.mkdtemp())
os.environ.update(APOLLO_DATA=str(ROOT / "data"), APOLLO_SECRETS=str(ROOT / "secrets"), APOLLO_RUN=str(ROOT / "run"),
                  APOLLO_UPDATES=str(ROOT / "updates"))
(ROOT / "data").mkdir()
(ROOT / "secrets").mkdir()
(ROOT / "data" / "device.token").write_text(hashlib.sha256(b"phone-token").hexdigest())
(ROOT / "secrets" / "mcp.token").write_text(hashlib.sha256(b"mcp-token").hexdigest())
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest  # noqa: E402

from apollo import apps, seed  # noqa: E402
from apollo.store import Store  # noqa: E402
from apollo.tools import Tools  # noqa: E402

PHONE = {"Authorization": "Bearer phone-token"}


class FakeBus:
    def __init__(self):
        self.sent = []

    def deliver(self, agent, thread, mid, as_brain=False):
        self.sent.append((agent, thread, mid))

    def event(self, agent, thread, kind, text):
        self.sent.append((agent, thread, kind))


@pytest.fixture
def tools():
    return Tools(Store(ROOT / f"t{os.urandom(4).hex()}.db"), FakeBus())


def test_agents_write_text_only_and_never_a_phone_table(tools):
    seed.plant()
    assert "Not done" in tools.run("athena", "app_write", {"app": "drives", "path": "x.png", "text": "a", "why": "w"})
    assert "Not done" in tools.run("athena", "app_sql", {"app": "drives", "sql": "DELETE FROM trips", "why": "w"})
    assert "Not done" in tools.run("athena", "app_write", {"app": "drives", "path": "../routines/README.md", "text": "a", "why": "w"})
    assert "Added" in tools.run("athena", "app_write", {"app": "drives", "path": "notes/drives.md", "text": "# Drives", "why": "w"})
    assert "Not done" in tools.run("athena", "app_write", {"app": "drives", "path": "pages/a.html", "text": "<iframe src=x>", "why": "w"})


def test_facts_land_in_their_app_table():
    seed.plant()
    seed.ingest("trip", 1, {"start": 1, "end": 2, "miles": 14.2, "minutes": 23, "points": [[1, 2, 3]]})
    assert apps.sql("drives", "SELECT miles FROM trips")[-1]["miles"] == 14.2


def test_archived_apps_restore_exactly(tools):
    tools.run("athena", "app_create", {"name": "scratch", "title": "Scratch", "about": "fill-ups", "why": "w"})
    tools.run("athena", "app_write", {"app": "scratch", "path": "notes/car.md", "text": "navy", "why": "w"})
    assert "Archived" in tools.run("athena", "app_archive", {"name": "scratch", "why": "w"})
    apps.restore("scratch", "owner")
    assert apps.read("scratch", "notes/car.md") == "navy"


def test_ask_iris_runs_in_a_visible_thread(tools):
    out = tools.run("athena", "ask_iris", {"job": "Find the Delta booking"})
    assert "thread" in out and tools.bus.sent[-1][0] == "iris"
    assert tools.store.outbox(0)[-1]["kind"] == "message" and tools.store.outbox(0)[-1]["data"]["agent"] == "iris"


def test_a_message_tells_the_phone_its_threads_kind(tools):
    s = tools.store
    for t, kind in ((s.facts_thread(), "facts"), (s.thread("Hi"), "chat")):
        s.send("message", {"thread": t, "id": 1, "from": "athena", "text": "x"})
        assert s.outbox(0)[-1]["data"]["kind"] == kind
    assert s.outbox(0)[-1]["data"]["title"] == "Hi"


def test_phone_actions_are_only_queued(tools):
    tools.run("athena", "phone_do", {"action": "open_garage", "why": "x"})
    item = tools.store.outbox(0)[-1]
    assert item["kind"] == "action" and item["data"]["action"] == "open_garage"


def test_phone_routes_need_the_token_and_mcp_lists_tools():
    from fastapi.testclient import TestClient
    from apollo import main
    c = TestClient(main.app)
    assert c.get("/v1/threads").status_code == 401
    assert c.get("/v1/threads", headers=PHONE).status_code == 200
    assert c.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).status_code == 401
    r = c.post("/mcp", headers={"Authorization": "Bearer mcp-token"}, json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert "ask_iris" in {t["name"] for t in r.json()["result"]["tools"]}
    r = c.post("/mcp", headers={"Authorization": "Bearer mcp-token"},
               json={"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "usage", "arguments": {}}})
    assert json.loads(r.json()["result"]["content"][0]["text"])["cap_usd"] == 200.0


def test_the_brain_switches_to_iris_at_the_cap(tools):
    from apollo.brain import Brain
    b = Brain(tools.store, tools.bus)
    tools.store.record_usage("s1", 201.0)
    assert b.active() == "iris"


def test_routine_times_follow_their_zone():
    import datetime as dt
    from apollo.routines import due, matches
    row = {"on_off": 1, "when_time": "07:00", "tz": "Asia/Kolkata", "repeat": "weekdays"}
    mon_7_ist = dt.datetime(2026, 10, 26, 1, 30, tzinfo=dt.timezone.utc)   # Monday 07:00 in Kolkata
    assert due(row, mon_7_ist)
    assert not due(dict(row, repeat="weekends"), mon_7_ist)
    assert matches({"on_off": 1, "when_trigger": "car:connected"}, "car", {"connected": True, "state": "connected", "at": 1})
    assert not matches({"on_off": 0, "when_trigger": "car"}, "car", {})


def test_phone_tables_hold_against_tricks(tools):
    seed.plant()
    for sql in ("WITH x AS (SELECT 1) DELETE FROM trips",
                "CREATE TRIGGER t AFTER INSERT ON trips BEGIN DELETE FROM trips; END",
                "ATTACH DATABASE 'x.db' AS x",
                "PRAGMA writable_schema = 1",
                "UPDATE \"trips\" SET miles = 0"):
        assert "Not done" in tools.run("athena", "app_sql", {"app": "drives", "sql": sql, "why": "w"}), sql
    assert isinstance(apps.sql("drives", "PRAGMA table_info(trips)"), list)


def test_group_messages_reach_each_agent_on_its_own(tools):
    s = tools.store
    t = s.thread("Weekend plan", agent="group")
    mid = s.post(t, "owner", "Who has Saturday?")
    assert {r["id"] for r in s.undelivered("iris")} >= {mid} and {r["id"] for r in s.undelivered("athena")} >= {mid}
    s.delivered(mid, "athena")
    assert mid in {r["id"] for r in s.undelivered("iris")}      # Athena reading it doesn't hide it from Iris
    assert mid not in {r["id"] for r in s.undelivered("athena")}


def test_private_chats_stay_private(tools):
    s = tools.store
    t = s.thread("Just us", agent="iris")
    mid = s.post(t, "owner", "hi", "iris")
    assert mid in {r["id"] for r in s.undelivered("iris")}
    assert mid not in {r["id"] for r in s.undelivered("athena")}


def test_the_group_pauses_after_a_long_agent_only_run(tools):
    from apollo.agents import Bus
    s = tools.store
    bus = Bus(s)
    sent = []
    bus.deliver = lambda agent, thread, mid, as_brain=False: sent.append(agent)
    t = s.thread("Loop", agent="group")
    s.post(t, "owner", "go")
    for i in range(25):
        s.post(t, "iris" if i % 2 else "athena", f"m{i}")
    bus.group(t, 1, "athena")
    assert sent == [] and s.outbox(0)[-1]["data"]["title"] == "The group chat paused"


def _seed(tmp, name, schema="CREATE TABLE IF NOT EXISTS things (id INTEGER PRIMARY KEY, what TEXT);", routines=()):
    d = tmp / name
    (d / "pages").mkdir(parents=True)
    (d / "app.json").write_text(json.dumps({"title": name.title(), "about": "a test seed", "pages": ["pages/index.html"],
                                            "phone_tables": ["things"], "routines": list(routines)}))
    (d / "schema.sql").write_text(schema)
    (d / "README.md").write_text("for Athena")
    (d / "pages" / "index.html").write_text("<p>hi</p>")
    return d


def test_seed_folders_plant_once_and_stay_hers(tmp_path):
    seed.plant()
    _seed(tmp_path, "seedling", routines=[{"name": "Water", "when_time": "07:00", "repeat": "daily", "action": "water it"}])
    seed.plant_seeds(tmp_path)
    m = apps.manifest("seedling")
    assert m["pages"] == ["pages/index.html"] and m["phone_tables"] == ["things"] and "routines" not in m
    assert apps.read("seedling", "README.md") == "for Athena" and not (apps.root() / "seedling" / "schema.sql").exists()
    assert apps.sql("seedling", "SELECT count(*) AS n FROM things")[0]["n"] == 0
    assert apps.sql("routines", "SELECT action FROM routines WHERE name = 'Water'") == [{"action": "water it"}]
    apps.write("seedling", "README.md", "hers now", "w", "athena")
    seed.plant_seeds(tmp_path)                                  # a restart never overwrites her app or doubles its routines
    assert apps.read("seedling", "README.md") == "hers now"
    assert len(apps.sql("routines", "SELECT id FROM routines WHERE name = 'Water'")) == 1
    apps.archive("seedling", "w", "owner")
    seed.plant_seeds(tmp_path)                                  # killed stays killed
    assert "seedling" not in {a["name"] for a in apps.list_apps()}


def test_a_broken_seed_leaves_nothing_behind(tmp_path):
    _seed(tmp_path, "broken", schema="CREATE TABLE nope (")
    seed.plant_seeds(tmp_path)
    assert "broken" not in {a["name"] for a in apps.list_apps()} and not (apps.root() / "broken").exists()


def test_the_real_seeds_plant_with_their_tables(tools):
    seed.plant()
    names = {a["name"] for a in apps.list_apps()}
    assert {"people", "memory", "theogony", "dawn", "brief", "closet", "trips", "calliope", "weather"} <= names
    for app in ("people", "memory", "theogony", "dawn", "brief", "closet", "trips", "calliope"):
        m = apps.manifest(app)
        assert m["pages"] and all((apps.root() / app / p).is_file() for p in m["pages"] + m["widgets"]), app
        assert (apps.root() / app / "README.md").is_file() and any((apps.root() / app / "notes").glob("*.md")), app
    # Calliope keeps Zeus's shape, so the journal moved over from Zeus opens in it
    cols = {r["name"] for r in apps.sql("calliope", "PRAGMA table_info(entries)")}
    assert cols == {"id", "at", "raw", "audio", "seconds", "filed"}
    assert len(apps.sql("theogony", "SELECT id FROM questions WHERE answer IS NULL")) == 9
    assert {r["name"] for r in apps.sql("routines", "SELECT name FROM routines")} >= {"Tidy memory", "Morning brief"}


def test_his_taps_and_apollos_setup_land_in_their_tables(tools):
    seed.plant()
    apps.sql("memory", "INSERT INTO facts (fact, topic) VALUES ('Drives a Civic', 'car')", (), "w", "athena")
    fid = apps.sql("memory", "SELECT max(id) AS id FROM facts")[0]["id"]
    seed.ingest("page_write", 1, {"app": "memory", "sql": "UPDATE facts SET confirmed = 1, updated = ? WHERE id = ?", "params": [5, fid]})
    seed.ingest("page_write", 1, {"app": "memory", "sql": "DELETE FROM facts WHERE id = ?", "params": [fid]})   # a page can't delete
    assert apps.sql("memory", f"SELECT confirmed FROM facts WHERE id = {fid}") == [{"confirmed": 1}]
    seed.ingest("setup", 7, {"key": "usage", "ok": True, "detail": "granted"})
    assert apps.sql("dawn", "SELECT ok, at FROM status WHERE key = 'usage'") == [{"ok": 1, "at": 7}]
    assert "Not done" in tools.run("athena", "app_sql", {"app": "dawn", "sql": "UPDATE status SET ok = 1", "why": "w"})


def test_activity_sends_only_the_tool_and_debounces(tools):
    s = tools.store
    s.activity("athena", "web_search")
    s.activity("athena", "app_read", "apollo")      # inside the 2s gap: held, not pushed
    pushed = [o["data"] for o in s.outbox(0) if o["kind"] == "activity"]
    assert pushed == [{"agent": "athena", "tool": "web_search", "server": "", "at": pushed[0]["at"]}]
    assert s.setting("activity")["athena"]["tool"] == "app_read"
    s.activity("athena", "idle")                     # idle always goes, and drops the held one
    s.activity("iris", "Bash")                       # each agent has its own gap
    pushed = [(o["data"]["agent"], o["data"]["tool"]) for o in s.outbox(0) if o["kind"] == "activity"]
    assert pushed == [("athena", "web_search"), ("athena", "idle"), ("iris", "Bash")]


def test_a_held_activity_goes_out_when_the_gap_ends(tools, monkeypatch):
    s = tools.store
    monkeypatch.setattr(s, "ACTIVITY_GAP", 0.05)
    s.activity("atlas", "app_sql")
    s.activity("atlas", "card")
    import time
    time.sleep(0.3)
    assert [o["data"]["tool"] for o in s.outbox(0) if o["kind"] == "activity"] == ["app_sql", "card"]


def test_brain_events_become_activity_and_idle_after_the_run(tools):
    from types import SimpleNamespace as E
    from apollo.brain import Brain
    b = Brain.__new__(Brain)
    b.store = tools.store
    b._activity(E(type="agent.mcp_tool_use", name="search_threads", mcp_server_name="Gmail", input={"q": "secret"}))
    b._activity(E(type="span.model_request_start"))
    b._idle()
    b._idle()                                        # nothing shown since: no second idle
    pushed = [o["data"] for o in tools.store.outbox(0) if o["kind"] == "activity"]
    assert [(p["tool"], p["server"]) for p in pushed] == [("search_threads", "Gmail"), ("idle", "")]
    assert all("secret" not in json.dumps(p) for p in pushed)


def test_hook_tool_names_split_into_tool_and_server():
    from apollo.agents import split_tool
    assert split_tool("mcp__gmail__search_threads") == ("search_threads", "gmail")
    assert split_tool("Bash") == ("Bash", "")


def test_pixel_guide_copy_and_its_example_stay_true():
    import re
    root = Path(__file__).resolve().parents[2]
    doc = (root / "docs" / "PIXEL.md").read_text(encoding="utf-8")
    demo = root / "server" / "seed" / "pixel-demo"
    assert (demo / "README.md").read_text(encoding="utf-8") == doc        # Athena reads the copy with app_read
    example = re.findall(r"```json\n(.*?)```", doc, re.S)[1]
    assert json.loads(example) == json.loads((demo / "pixel" / "rain.json").read_text(encoding="utf-8"))


def test_activity_wakes_rarely_and_not_while_the_phone_polls(tools):
    from apollo import fcm
    s = tools.store
    fcm._last_activity[0] = 0.0
    s.phone_seen = 1000.0
    assert not fcm.should_wake(s, "activity", 1010.0)               # Apollo on screen: it is polling already
    assert fcm.should_wake(s, "activity", 1040.0)
    assert not fcm.should_wake(s, "activity", 1060.0)                # one per 30s
    assert fcm.payload("tok", "activity")["message"]["android"] == {"priority": "normal", "collapse_key": "activity", "ttl": "300s"}
    assert fcm.payload("tok")["message"]["android"]["priority"] == "high"


def test_atlas_goes_idle_after_his_last_tool(tools, monkeypatch):
    import time
    from apollo import agents
    monkeypatch.setitem(agents.IDLE_AFTER, "atlas", 0.1)
    bus = agents.Bus(tools.store)
    tools.store.ACTIVITY_GAP = 0
    bus.activity("atlas", "app_read", "apollo")
    bus.activity("iris", "Bash")
    time.sleep(0.4)
    got = [(o["data"]["agent"], o["data"]["tool"]) for o in tools.store.outbox(0) if o["kind"] == "activity"]
    assert got == [("atlas", "app_read"), ("iris", "Bash"), ("atlas", "idle")]


def test_phone_reads_the_latest_activity():
    from fastapi.testclient import TestClient
    from apollo import main
    main.store.set_setting("activity", {"athena": {"agent": "athena", "tool": "web_search", "server": "", "at": 1.0}})
    c = TestClient(main.app)
    assert c.get("/v1/activity").status_code == 401
    assert c.get("/v1/activity", headers=PHONE).json()["athena"]["tool"] == "web_search"
    assert main.store.phone_seen > 0


def test_cards_take_until_and_countdown(tools):
    from apollo.tools import when_ms
    tools.run("athena", "card", {"title": "Flight", "body": "DL 412", "until": "2026-10-30T18:00", "countdown": 1793397600})
    d = tools.store.outbox(0)[-1]["data"]
    assert d["until"] == when_ms("2026-10-30T18:00-04:00") and d["countdown"] == 1793397600000


def test_phone_do_cancel_needs_an_id_and_calendar_times_become_ms(tools):
    assert "Not done" in tools.run("athena", "phone_do", {"action": "cancel", "args": {}, "why": "w"})
    tools.run("athena", "phone_do", {"action": "cancel", "args": {"id": 7}, "why": "he did it himself"})
    assert tools.store.outbox(0)[-1]["data"]["args"] == {"id": 7}
    tools.run("athena", "phone_do", {"action": "calendar_update", "args": {"id": "12", "start": "2026-11-02T09:00Z"}, "why": "w"})
    assert tools.store.outbox(0)[-1]["data"]["args"]["start"] == 1793610000000


def test_notify_carries_open_time_repeat_and_check(tools):
    from apollo.tools import when_ms
    out = tools.run("athena", "notify", {"title": "Leave", "open": "Uber", "at": "2026-10-30T17:40", "repeat": "mon,thu", "check_after_min": 10})
    d = tools.store.outbox(0)[-1]["data"]
    assert d["at"] == when_ms("2026-10-30T17:40-04:00") and d["repeat"] == "mon,thu" and d["tz"] == "America/New_York"
    assert d["open"] == "Uber" and d["check_after_min"] == 10 and d["body"] == "" and "notify_unseen" in out
    assert "Not done" in tools.run("athena", "notify", {"title": "x", "repeat": "daily"})
    assert "Not done" in tools.run("athena", "notify", {"title": "x", "at": 1793397600, "repeat": "fortnightly"})
    assert "Not done" in tools.run("athena", "notify", {"title": "x", "check_after_min": 0})
    tools.run("athena", "notify", {"title": "", "cancel": 41})
    assert tools.store.outbox(0)[-1]["data"] == {"cancel": "41", "from": "athena"}


def test_call_owner_critical_later_unless_seen(tools):
    out = tools.run("athena", "call_owner", {"reason": "flight", "text": "Wake up", "critical": True, "after_minutes": 20, "unless_seen": True})
    d = tools.store.outbox(0)[-1]["data"]
    assert d["critical"] and d["after_minutes"] == 20 and d["unless_seen"] and "Do Not Disturb" in out
    assert "Not done" in tools.run("athena", "call_owner", {"reason": "r", "text": "t", "unless_seen": True})
    tools.run("athena", "call_owner", {"reason": "r", "text": "t"})
    assert tools.store.outbox(0)[-1]["data"] == {"reason": "r", "text": "t", "from": "athena"}


def test_reminders_need_text_and_a_time(tools):
    assert "Not done" in tools.run("athena", "phone_do", {"action": "reminder", "args": {"text": "milk"}, "why": "w"})
    tools.run("athena", "phone_do", {"action": "reminder", "args": {"text": "milk", "at": "2026-11-02T09:00Z"}, "why": "w"})
    assert tools.store.outbox(0)[-1]["data"]["args"]["at"] == 1793610000000


def test_thread_choices_reach_the_phone_and_the_thread(tools):
    t = tools.store.thread("Dinner")
    tools.run("athena", "thread_post", {"thread": t, "text": "Thai or pizza?", "choices": ["Thai", "Pizza", " "]})
    assert tools.store.outbox(0)[-1]["data"]["choices"] == ["Thai", "Pizza"]
    assert tools.store.messages(t)[-1]["choices"] == ["Thai", "Pizza"]
    tools.run("athena", "thread_post", {"thread": t, "text": "ok"})
    assert tools.store.messages(t)[-1]["choices"] == []


def test_alerts_open_resolve_and_server_health(tools):
    s = tools.store
    s.set_status("fcm", True, "ok")
    s.set_status("fcm", False, "HTTP 500")
    s.set_status("fcm", False, "HTTP 500")          # still down: one alert, not two
    assert [a["source"] for a in s.alerts()] == ["fcm"]
    s.set_status("fcm", True, "wake sent")
    assert s.alerts() == [] and s.alerts(True)[0]["resolved_by"] == "apollo"
    aid = s.alert("disk", "high", "disk 95% full")
    assert s.outbox(0)[-1]["data"]["priority"] == "high"
    assert "Not done" in tools.run("iris", "alerts", {"resolve": 999})
    assert "resolved" in tools.run("iris", "alerts", {"resolve": aid, "note": "cleared logs"})
    h = json.loads(tools.run("athena", "server_health", {}))
    assert h["open_alerts"] == 0 and "disk_free_gb" in h and "components" in h


def test_one_shot_routines_turn_off_after_running(tools):
    from apollo import routines
    seed.plant()
    routines.migrate()
    apps.sql("routines", "INSERT INTO routines(name, when_trigger, action, once) VALUES('kohls', 'place:kohls', 'remind the return', 1)")
    row = apps.sql("routines", "SELECT * FROM routines WHERE name = 'kohls'")[0]

    class B:
        def enqueue(self, t): pass
    c = routines.Clock.__new__(routines.Clock)
    c.store, c.brain = tools.store, B()
    c.on_fact("place", {"name": "kohls", "event": "arrive"})
    assert apps.sql("routines", "SELECT on_off FROM routines WHERE id = ?", (row["id"],))[0]["on_off"] == 0
    assert "one-shot" in tools.store.messages(c._thread())[-1]["text"]
    assert tools.store.undelivered("athena")[-1]["thread"] == c._thread()


def test_stays_and_walks_land_and_old_apps_get_new_tables():
    seed.plant()
    seed.ingest("stay", 1, {"start": 1, "end": 400000, "lat": 42.4, "lng": -71.0})
    seed.ingest("stay", 1, {"start": 5, "lat": 42.4, "lng": -71.0})             # still there: facts only
    assert apps.sql("drives", "SELECT COUNT(*) AS n FROM stays WHERE start = 5")[0]["n"] == 0
    seed.ingest("health_walk", 1, {"id": "w1", "start": 1, "end": 2, "kind": "walking", "steps": 900})
    assert apps.sql("health", "SELECT kind FROM walks WHERE id = 'w1'")[0]["kind"] == "walking"
    m = apps.manifest("health")
    m["phone_tables"] = ["days"]
    (apps.root() / "health" / "app.json").write_text(json.dumps(m))
    seed.plant()
    assert "walks" in apps.manifest("health")["phone_tables"]


def test_reactions_go_both_ways_and_never_relay(tools):
    from apollo import chat
    s = tools.store
    t = s.thread("Plans", agent="group")
    a = s.post(t, "athena", "Dinner at 7?")
    i = s.post(t, "iris", "Booked.")
    chat.reacted(s, tools.bus, a, "👍", True)
    chat.reacted(s, tools.bus, i, "❤️", True)
    assert s.take_notes(t, "athena") == [f"[Owner reacted 👍 to your message {a}: Dinner at 7?]"]
    assert tools.bus.sent == [("iris", t, "reaction")]           # an event, no delivery: nothing relays in the group
    assert s.outbox(0)[-1]["kind"] == "reaction"
    assert "Reacted" in tools.run("iris", "react", {"message": a, "emoji": "👍"})
    assert [r["who"] for r in s.messages(t)[0]["reactions"]] == ["owner", "iris"]
    chat.reacted(s, tools.bus, a, "👍", False)
    assert [r["who"] for r in s.messages(t)[0]["reactions"]] == ["iris"]
    private = s.thread("Just us", agent="athena")
    p = s.post(private, "owner", "secret", "athena")
    assert "Not done" in tools.run("iris", "react", {"message": p, "emoji": "👍"})


def test_replies_quote_within_the_thread(tools):
    s = tools.store
    t = s.thread("Car", agent="athena")
    other = s.thread("Other", agent="athena")
    q = s.post(t, "owner", "When is the oil change due?", "athena")
    x = s.post(other, "owner", "elsewhere", "athena")
    tools.run("athena", "thread_post", {"thread": t, "text": "Nov 3.", "reply_to": q})
    tools.run("athena", "thread_post", {"thread": t, "text": "And tires.", "reply_to": x})
    rows = s.messages(t)
    assert rows[1]["reply_to"] == q and rows[2]["reply_to"] is None
    assert s.outbox(0)[-2]["data"]["reply_to"] == q
    assert s.render(s.message(rows[1]["id"])).startswith("[replying to Owner: «When is the oil change due?»]\n")


def test_edits_and_deletes_reach_only_agents_that_read_it(tools):
    from apollo import chat
    s = tools.store
    t = s.thread("Trip", agent="athena")
    m = s.post(t, "owner", "Book Friday", "athena")
    chat.edited(s, tools.bus, m, "Book Saturday")
    assert s.take_notes(t, "athena") == [] and s.message(m)["text"] == "Book Saturday"   # not read yet: she just gets the new text
    s.delivered(m, "athena")
    chat.edited(s, tools.bus, m, "Book Sunday")
    assert "It now says: Book Sunday" in s.take_notes(t, "athena")[0]
    chat.reacted(s, tools.bus, m, "👍", True)
    chat.deleted(s, tools.bus, m)
    row = s.messages(t)[0]
    assert row["deleted"] == 1 and row["text"] == "" and row["reactions"] == []
    assert "deleted his message" in s.take_notes(t, "athena")[0]
    assert [o["kind"] for o in s.outbox(0)][-2:] == ["reaction", "delete"]


def test_he_archives_and_restores_his_threads():
    from fastapi.testclient import TestClient
    from apollo import main
    c = TestClient(main.app)
    s = main.store
    t = s.thread("Trip")
    archived = lambda: next(r["archived"] for r in c.get("/v1/threads", headers=PHONE).json() if r["id"] == t)
    assert c.post(f"/v1/threads/{t}/archive").status_code == 401
    assert c.post(f"/v1/threads/{t}/archive", headers=PHONE).status_code == 200 and archived() == 1
    assert c.post(f"/v1/threads/{t}/restore", headers=PHONE).status_code == 200 and archived() == 0
    c.post(f"/v1/threads/{t}/archive", headers=PHONE)
    s.post(t, "athena", "back again")          # a new message brings it back
    assert archived() == 0
    assert c.post(f"/v1/threads/{s.facts_thread()}/archive", headers=PHONE).status_code == 404
    assert c.post(f"/v1/threads/{t}/burn", headers=PHONE).status_code == 404
    assert c.post("/v1/threads/99999/archive", headers=PHONE).status_code == 404


def test_phone_routes_for_reactions_edits_uploads_and_mentions():
    from fastapi.testclient import TestClient
    from apollo import main
    c = TestClient(main.app)
    main.bus.deliver = lambda *a, **k: None
    s = main.store
    t = s.thread("Group", agent="group")
    a = s.post(t, "athena", "hi")
    assert c.post(f"/v1/messages/{a}/edit", headers=PHONE, json={"text": "x"}).status_code == 404      # not his
    assert c.post(f"/v1/messages/{a}/react", headers=PHONE, json={"emoji": "😂"}).status_code == 200
    up = c.post("/v1/uploads?name=list.txt", headers=dict(PHONE, **{"content-type": "text/plain"}), content=b"milk, eggs").json()["upload"]
    r = c.post("/v1/chat", headers=PHONE, json={"thread": t, "text": "@iris for you", "upload": up, "reply_to": a}).json()
    m = s.message(r["message"])
    assert m["reply_to"] == a and m["upload"] == up
    said = s.render(m)
    assert said.startswith("[for: iris]\n[replying to Athena: «hi»]") and f"upload {up}" in said
    assert "milk, eggs" in main.tools.run("iris", "upload_read", {"upload": up})
    assert c.post(f"/v1/messages/{m['id']}/edit", headers=PHONE, json={"text": "@iris now"}).status_code == 200
    assert c.post(f"/v1/messages/{m['id']}/delete", headers=PHONE).status_code == 200
    assert c.post(f"/v1/messages/{m['id']}/edit", headers=PHONE, json={"text": "again"}).status_code == 404   # a tombstone stays one
    s.set_setting("upload_max_mb", 0)
    assert c.post("/v1/uploads", headers=PHONE, content=b"x").status_code == 413
    s.set_setting("upload_max_mb", 20)


def test_uploads_stay_in_their_chat_and_images_go_as_images(tools):
    from apollo import chat
    s = tools.store
    png = chat.save_upload(s, "../../shot.png", "image/png", b"\x89PNG fake")
    t = s.thread("Mine", agent="athena")
    s.post(t, "owner", "look", "athena", upload=png)
    assert "Not done" in tools.run("iris", "upload_read", {"upload": png})
    content = tools.content("athena", "upload_read", {"upload": png})
    assert content[1]["type"] == "image" and content[1]["mimeType"] == "image/png"
    assert s.upload(png)["name"] == "shot.png"


def test_attached_photo_goes_to_each_agent_as_a_path_it_opens(tools, tmp_path, monkeypatch):
    from apollo import chat, config
    s = tools.store
    monkeypatch.setattr(config, "AGENT_FILES", tmp_path / "agents")
    monkeypatch.setattr(config, "BOX_FILES", tmp_path / "box")
    jpg = chat.save_upload(s, "cat pic.jpg", "image/jpeg", b"jpeg fake")
    m = s.message(s.post(s.thread("Mine", agent="group"), "owner", "look", None, upload=jpg))
    assert f"upload {jpg}: read it with upload_read" in s.render(m, "iris")      # no folder yet: the old way
    (tmp_path / "agents").mkdir(); (tmp_path / "box").mkdir()
    copy = r"\d+-[0-9a-f]{32}-cat_pic\.jpg"
    assert re.search(rf"photo at {re.escape(str(tmp_path / 'agents'))}/{copy} ", s.render(m, "iris"))
    assert re.search(rf"photo at /workspace/uploads/{copy} ", s.render(m, "athena"))
    assert s.render(m, "athena") == s.render(m, "athena")                       # one copy, same name each time
    [boxed] = (tmp_path / "box").iterdir()
    assert re.fullmatch(copy, boxed.name) and boxed.read_bytes() == b"jpeg fake"
    assert "upload_read" in s.render(m)                                          # a facts turn: no file tools
    s.delete(m["id"])
    assert not list((tmp_path / "agents").iterdir()) and not list((tmp_path / "box").iterdir())


def test_a_copy_in_flight_never_outlives_the_delete(tools, tmp_path, monkeypatch):
    import threading
    from apollo import chat, config
    s = tools.store
    monkeypatch.setattr(config, "AGENT_FILES", tmp_path / "agents")
    (tmp_path / "agents").mkdir()
    jpg = chat.save_upload(s, "a.jpg", "image/jpeg", b"jpeg")
    m = s.message(s.post(s.thread("Mine", agent="iris"), "owner", "look", None, upload=jpg))
    f = s.upload(jpg)
    gone = threading.Thread(target=s.delete, args=(m["id"],))
    real = os.chmod

    def slow(p, mode):                      # the delete runs while the copy is written but not yet moved in
        real(p, mode)
        gone.start()
        gone.join(0.3)
    monkeypatch.setattr(chat.os, "chmod", slow)
    assert chat.agent_file(f, "iris") is None      # its upload went mid-copy: nothing published
    gone.join()
    assert chat.agent_file(f, "iris") is None      # and a late copy finds nothing to copy
    assert not list((tmp_path / "agents").iterdir())


def test_lemnos_reads_only_its_roots():
    from fastapi.testclient import TestClient
    from apollo import config, main
    c = TestClient(main.app)
    (config.WORKSPACE / "notes").mkdir(parents=True, exist_ok=True)
    (config.WORKSPACE / "notes" / "bugs.md").write_text("# Bugs")
    assert c.get("/v1/lemnos/status").status_code == 401
    assert "mem_total" in c.get("/v1/lemnos/status", headers=PHONE).json()
    assert c.get("/v1/lemnos/files", headers=PHONE).json()["roots"] == ["workspace", "apps"]
    assert c.get("/v1/lemnos/files", headers=PHONE, params={"root": "workspace", "path": "notes"}).json()["entries"][0]["name"] == "bugs.md"
    assert c.get("/v1/lemnos/text", headers=PHONE, params={"root": "workspace", "path": "notes/bugs.md"}).json()["text"] == "# Bugs"
    for root, path in (("workspace", "../device.token"), ("data", ""), ("workspace", ".hidden"), ("apps", "../../device.token")):
        assert c.get("/v1/lemnos/files", headers=PHONE, params={"root": root, "path": path}).status_code == 404


def test_lemnos_writes_stay_in_roots_and_off_data_db():
    from fastapi.testclient import TestClient
    from apollo import config, main
    c = TestClient(main.app)
    (config.WORKSPACE / "w").mkdir(parents=True, exist_ok=True)
    (config.APPS / "box").mkdir(parents=True, exist_ok=True)
    (config.APPS / "box" / "data.db").write_bytes(b"db")
    put = lambda root, path, body, **q: c.put("/v1/lemnos/file", headers=PHONE, params={"root": root, "path": path, **q}, content=body)
    assert c.put("/v1/lemnos/file", params={"root": "workspace", "path": "w/a.md"}, content=b"x").status_code == 401
    assert put("workspace", "w/a.md", b"one").status_code == 200
    assert put("workspace", "w/a.md", b"two").status_code == 409                     # uploads never overwrite
    assert put("workspace", "w/a.md", b"two", replace=1).status_code == 200          # an edit does
    assert (config.WORKSPACE / "w" / "a.md").read_text() == "two"
    assert put("workspace", "w/a.md", b"\xff\xfe", replace=1).status_code == 415
    for root, path in (("workspace", "../x"), ("workspace", "w/.env"), ("apps", "box/data.db"), ("apps", "box/data.db-wal"), ("data", "x"), ("workspace", "nope/x")):
        assert put(root, path, b"x").status_code in (400, 404, 409), (root, path)
    assert put("apps", "box/data.db", b"x", replace=1).status_code == 400
    post = lambda route, **b: c.post(f"/v1/lemnos/{route}", headers=PHONE, json=b)
    assert post("rename", root="workspace", path="w/a.md", to="b.md").status_code == 200
    assert (config.WORKSPACE / "w" / "b.md").exists()
    for to in ("../b.md", "x/y", ".hidden", "data.db"):
        assert post("rename", root="workspace", path="w/b.md", to=to).status_code == 400
    assert post("rename", root="apps", path="box/data.db", to="x").status_code == 400
    assert post("delete", root="apps", path="box/data.db").status_code == 400
    assert post("delete", root="apps", path="box").status_code == 403                # a folder holding an app's data
    assert post("delete", root="workspace", path="").status_code == 400              # never a root
    assert post("delete", root="workspace", path="w/b.md").status_code == 200
    assert not (config.WORKSPACE / "w" / "b.md").exists() and (config.APPS / "box" / "data.db").exists()


def test_lemnos_terminal_and_units_refuse_what_isnt_allowed(monkeypatch, tmp_path):
    from fastapi.testclient import TestClient
    from starlette.websockets import WebSocketDisconnect
    from apollo import lemnos, main
    c = TestClient(main.app)
    calls = []
    monkeypatch.setattr(lemnos, "helper", lambda prefix, *a, **k: calls.append((prefix[-2:], a)) or b'[{"name":"claude-auto"},{"name":"shell-1"}]')
    assert c.get("/v1/lemnos/sessions", headers=PHONE).json()["sessions"] == [{"name": "claude-auto"}, {"name": "shell-1"}]   # Iris's too
    assert c.delete("/v1/lemnos/sessions/claude-auto", headers=PHONE).status_code == 403
    for q in ("session=claude-auto", "session=CLAUDE-AUTO", "session="):
        with pytest.raises(WebSocketDisconnect):
            with c.websocket_connect("/v1/lemnos/term?" + q, headers=PHONE) as ws:
                ws.receive_text()
    with pytest.raises(WebSocketDisconnect):
        with c.websocket_connect("/v1/lemnos/term?session=shell-1") as ws:     # no token
            ws.receive_text()
    cfg = tmp_path / "lemnos.json"
    cfg.write_text('{"units": ["caddy.service", "apollo-desktop.service"]}')
    monkeypatch.setattr(lemnos, "CONTROL", cfg)
    assert c.post("/v1/lemnos/unit", headers=PHONE, json={"unit": "iris.service", "action": "restart"}).status_code == 403
    assert c.post("/v1/lemnos/unit", headers=PHONE, json={"unit": "apollo-desktop.service", "action": "stop"}).status_code == 403
    assert c.post("/v1/lemnos/unit", headers=PHONE, json={"unit": "caddy.service", "action": "rm -rf"}).status_code == 422
    assert c.post("/v1/lemnos/unit", headers=PHONE, json={"unit": "caddy.service", "action": "restart"}).status_code == 200
    assert c.get("/v1/lemnos/status", headers=PHONE).json()["control"] == ["caddy.service"]
    assert not any("claude-auto" in a for _, a in calls) and calls[-1] == (["-n", lemnos.HELPER], ("unit", "restart", "caddy.service"))


def test_backups_are_sealed_files_only_and_kept_14():
    from fastapi.testclient import TestClient
    from apollo import main
    c = TestClient(main.app)
    bid = "20261026T030000Z-0000000{}"
    assert c.put("/v1/backup/" + bid.format(0), content=b"ABK1sealed").status_code == 401
    assert c.put("/v1/backup/" + bid.format(0), headers=PHONE, content=b"plain text").status_code == 400
    assert c.put("/v1/backup/../x", headers=PHONE, content=b"ABK1").status_code in (400, 404)
    for i in range(9):
        assert c.put("/v1/backup/" + bid.format(i), headers=PHONE, content=b"ABK1sealed").status_code == 200
    assert c.put("/v1/backup/" + bid.format(0), headers=PHONE, content=b"ABK1again").status_code == 409
    assert c.get("/v1/backup/" + bid.format(3), headers=PHONE).content == b"ABK1sealed"
    for i in range(6):
        c.put(f"/v1/backup/20261027T03000{i}Z-0000000a", headers=PHONE, content=b"ABK1sealed")
    assert len(c.get("/v1/backup", headers=PHONE).json()["backups"]) == 14
    assert c.get("/v1/update", headers=PHONE).json() == {}


class FakeTomTom:
    """urlopen stand-in: records the URLs, answers with `body` or raises `error`."""
    def __init__(self, body=None, error=None):
        self.urls, self.body, self.error = [], body, error

    def __call__(self, url, timeout):
        self.urls.append(url)
        if self.error:
            raise self.error
        import io
        return io.BytesIO(json.dumps(self.body).encode())


def test_tomtom_checks_args_and_needs_the_key(tools, monkeypatch):
    from apollo import config
    config.TOMTOM_KEY.unlink(missing_ok=True)
    assert "no TomTom key" in tools.run("athena", "tomtom", {"kind": "reverse", "at": "42.4,-71.0"})
    config.TOMTOM_KEY.write_text("abcDEF1234567890")
    fake = FakeTomTom({"addresses": []})
    monkeypatch.setattr("urllib.request.urlopen", fake)
    assert "Not done" in tools.run("athena", "tomtom", {"kind": "route", "from": "42.4", "to": "43,-78"})
    assert "Not done" in tools.run("athena", "tomtom", {"kind": "nearby", "at": "95,-78"})
    assert "Not done" in tools.run("athena", "tomtom", {"kind": "teleport"})
    assert fake.urls == []


def test_tomtom_route_reply_is_small_and_names_incidents(tools, monkeypatch):
    from apollo import config
    config.TOMTOM_KEY.write_text("abcDEF1234567890")
    sec = {"sectionType": "TRAFFIC", "delayInSeconds": 300, "tec": {"causes": [{"mainCauseCode": 2}]}}
    route = {"summary": {"travelTimeInSeconds": 1500, "trafficDelayInSeconds": 300, "lengthInMeters": 16093}, "sections": [sec],
             "legs": [{"points": [{"latitude": 42.4, "longitude": -71.0}]}]}
    fake = FakeTomTom({"routes": [route, {"summary": {"travelTimeInSeconds": 1800}}]})
    monkeypatch.setattr("urllib.request.urlopen", fake)
    out = json.loads(tools.run("athena", "tomtom", {"kind": "route", "from": "42.4,-71.0", "to": "42.5,-70.9", "arrive_at": "2026-10-30T09:00"}))
    assert out["minutes"] == 25 and out["miles"] == 10 and out["incidents"] == ["Crash, +5 min"] and out["alternatives"] == [30]
    assert "points" not in out and fake.urls[0].startswith("https://api.tomtom.com/routing/1/calculateRoute/42.40000,-71.00000:")
    assert "arriveAt=2026-10-30T09%3A00%3A00-04%3A00" in fake.urls[0]


def test_tomtom_key_never_leaks_from_an_error(tools, monkeypatch):
    import urllib.error
    from apollo import config
    config.TOMTOM_KEY.write_text("abcDEF1234567890")
    monkeypatch.setattr("urllib.request.urlopen", FakeTomTom(error=urllib.error.URLError("bad https://api.tomtom.com/x?key=abcDEF1234567890")))
    out = tools.run("athena", "tomtom", {"kind": "search", "query": "Wegmans", "at": "42.4,-71.0"})
    assert out.startswith("Not done: TomTom failed") and "abcDEF1234567890" not in out and "[key]" in out
    assert not tools.store.q("SELECT * FROM log WHERE what LIKE '%abcDEF%'")


def test_tomtom_stops_at_the_daily_cap(tools, monkeypatch):
    from apollo import config
    config.TOMTOM_KEY.write_text("abcDEF1234567890")
    fake = FakeTomTom({"results": [{"poi": {"name": "Wegmans", "categories": ["grocery"]}, "dist": 40.2, "position": {"lat": 1, "lon": 2}}]})
    monkeypatch.setattr("urllib.request.urlopen", fake)
    tools.store.set_setting("tomtom_daily_cap", 2)
    a = {"kind": "nearby", "at": "42.4,-71.0"}
    assert json.loads(tools.run("athena", "tomtom", a))[0]["name"] == "Wegmans"
    tools.run("athena", "tomtom", a)
    assert tools.run("athena", "tomtom", a) == "Not done: daily TomTom limit" and len(fake.urls) == 2


def test_phone_hands_over_the_tomtom_key():
    from fastapi.testclient import TestClient
    from apollo import config, main
    c = TestClient(main.app)
    assert c.post("/v1/tomtom_key", json={"key": "abcDEF1234567890"}).status_code == 401
    assert c.post("/v1/tomtom_key", headers=PHONE, json={"key": "not a key!"}).status_code == 400
    assert c.post("/v1/tomtom_key", headers=PHONE, json={"key": "abcDEF1234567890"}).status_code == 200
    assert config.TOMTOM_KEY.read_text() == "abcDEF1234567890"


NY = [{"EventType": "roadwork", "RoadwayName": "I-90", "DirectionOfTravel": "Westbound", "Description": "Lane closed near exit 50.",
       "Severity": "Minor", "Latitude": 42.45, "Longitude": -70.95},
      {"EventType": "accident", "RoadwayName": "I-190", "DirectionOfTravel": "northbound", "Description": "Crash near exit 9.",
       "Severity": "Major", "Latitude": 42.42, "Longitude": -71.08},
      {"EventType": "accident", "RoadwayName": "I-87", "DirectionOfTravel": "south", "Description": "Crash in Albany.",
       "Severity": "Major", "Latitude": 42.65, "Longitude": -73.75},
      {"EventType": "special event", "RoadwayName": "Main St", "Latitude": 42.43, "Longitude": -71.05}]


@pytest.fixture
def ny(monkeypatch):
    from apollo import config, ny511
    config.NY511_KEY.write_text("ny511KEY-123456789")
    ny511._cache.update(at=0.0, events=[])
    fake = FakeTomTom(NY)
    monkeypatch.setattr("urllib.request.urlopen", fake)
    return fake


def test_roads_filters_the_state_list_worst_first_and_keeps_it_5_min(tools, ny):
    out = json.loads(tools.run("athena", "roads", {"from": "42.41,-71.09", "to": "42.47,-70.90"}))
    assert out == ["Crash on I-190 N near exit 9", "Road work on I-90 W near exit 50"]
    assert json.loads(tools.run("athena", "roads", {"at": "42.65,-73.75", "radius_m": 2000})) == ["Crash on I-87 S"]
    assert len(ny.urls) == 1 and ny.urls[0] == "https://511ny.org/api/getevents?key=ny511KEY-123456789&format=json"
    assert "Not done" in tools.run("athena", "roads", {})


def test_roads_needs_the_key_hides_it_and_has_a_cap(tools, ny, monkeypatch):
    import urllib.error
    from apollo import config
    tools.store.set_setting("ny511_daily_cap", 1)
    monkeypatch.setattr("urllib.request.urlopen", FakeTomTom(error=urllib.error.URLError("https://511ny.org/api/getevents?key=ny511KEY-123456789")))
    out = tools.run("athena", "roads", {"at": "42.4,-71.0"})
    assert out.startswith("Not done: 511NY failed") and "ny511KEY" not in out
    assert tools.run("athena", "roads", {"at": "42.4,-71.0"}) == "Not done: daily 511NY limit"
    config.NY511_KEY.unlink()
    assert "no 511NY key" in tools.run("athena", "roads", {"at": "42.4,-71.0"})


def test_phone_hands_over_the_511ny_key():
    from fastapi.testclient import TestClient
    from apollo import config, main
    c = TestClient(main.app)
    assert c.post("/v1/ny511_key", json={"key": "ny511KEY-123456789"}).status_code == 401
    assert c.post("/v1/ny511_key", headers=PHONE, json={"key": "x"}).status_code == 400
    assert c.post("/v1/ny511_key", headers=PHONE, json={"key": "ny511KEY-123456789"}).status_code == 200
    assert config.NY511_KEY.read_text() == "ny511KEY-123456789"


def test_calypso_drops_are_checked_stamped_by_owner_and_forgotten_once_taken(monkeypatch):
    import base64 as b64
    from fastapi.testclient import TestClient
    from apollo import calypso, main
    sp = ROOT / "calypso-spool"
    sp.mkdir(exist_ok=True)
    monkeypatch.setenv("APOLLO_CALYPSO_SPOOL", str(sp))
    good = {"v": 1, "id": "abcdef0123456789", "kind": "secret", "label": "CloudHost token", "from": "atlas", "at": 1,
            "wk": b64.b64encode(b"k" * 256).decode(), "iv": b64.b64encode(b"i" * 12).decode(), "ct": b64.b64encode(b"c" * 40).decode()}
    (sp / "a.json").write_text(json.dumps(good))
    (sp / "b.json").write_text(json.dumps(dict(good, id="bad id!", origin="agents")))
    c = TestClient(main.app)
    assert c.get("/v1/calypso/drops").status_code == 401
    got = c.get("/v1/calypso/drops", headers=PHONE).json()["drops"]
    assert [d["id"] for d in got] == ["abcdef0123456789"] and got[0]["origin"] in ("unverified", "apollo", "agents")
    assert not list(sp.glob("*.json"))
    assert c.post("/v1/calypso/ack", headers=PHONE, json={"ids": ["abcdef0123456789", "../x"]}).json()["removed"] == 1
    assert c.get("/v1/calypso/drops", headers=PHONE).json()["drops"] == []
    assert c.post("/v1/calypso/key", headers=PHONE, json={"pem": "not a key"}).status_code == 400
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    pub = rsa.generate_private_key(public_exponent=65537, key_size=2048).public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    assert c.post("/v1/calypso/key", headers=PHONE, json={"pem": pub}).status_code == 200
    assert (ROOT / "run" / "calypso.pub.pem").read_text().startswith("-----BEGIN PUBLIC KEY-----")
    assert calypso.valid(good) and not calypso.valid(dict(good, **{"from": "mallory"}))


def test_pairing_signs_the_phones_certificate_request_when_the_device_ca_exists():
    import datetime as dt
    import time as _t
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
    from fastapi.testclient import TestClient
    from apollo import config, main
    ca_key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Apollo device CA")])
    now = dt.datetime.now(dt.timezone.utc)
    ca = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(ca_key.public_key()).serial_number(1)
          .not_valid_before(now).not_valid_after(now + dt.timedelta(days=1)).add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
          .sign(ca_key, hashes.SHA256()))
    config.DEVICE_CA_KEY.write_bytes(ca_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    config.DEVICE_CA.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    phone_key = ec.generate_private_key(ec.SECP256R1())
    csr = x509.CertificateSigningRequestBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "owner-fold")])).sign(phone_key, hashes.SHA256())
    before = config.DEVICE_TOKEN.read_text()
    c = TestClient(main.app)
    try:
        config.PAIR_CODE.write_text(f"amber-birch {_t.time() + 600}")
        assert c.post("/v1/pair", json={"code": "amber-birch", "token": "t" * 43, "csr": "junk"}).status_code == 400
        r = c.post("/v1/pair", json={"code": "amber-birch", "token": "t" * 43, "csr": csr.public_bytes(serialization.Encoding.PEM).decode()}).json()
        chain = x509.load_pem_x509_certificates(r["cert"].encode())
        assert len(chain) == 2 and chain[0].subject.rfc4514_string() == "CN=apollo-phone"
        assert chain[0].public_key().public_numbers() == phone_key.public_key().public_numbers()
        assert chain[0].extensions.get_extension_for_class(x509.ExtendedKeyUsage).value == x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH])
        ca_key.public_key().verify(chain[0].signature, chain[0].tbs_certificate_bytes, ec.ECDSA(hashes.SHA256()))
    finally:
        config.DEVICE_TOKEN.write_text(before)
        config.DEVICE_CA_KEY.unlink(missing_ok=True)
        config.DEVICE_CA.unlink(missing_ok=True)


def test_zeus_backups_are_listed_and_served_read_only():
    from fastapi.testclient import TestClient
    from apollo import config, main
    c = TestClient(main.app)
    assert c.get("/v1/backup/zeus", headers=PHONE).json() == {"backups": []}
    d = config.DATA / "zeus-backups"
    d.mkdir(exist_ok=True)
    (d / "20261020T030000Z-0badc0de.zbk").write_bytes(b"ZBK1sealed")
    assert c.get("/v1/backup/zeus").status_code == 401
    assert [b["id"] for b in c.get("/v1/backup/zeus", headers=PHONE).json()["backups"]] == ["20261020T030000Z-0badc0de"]
    assert c.get("/v1/backup/zeus/20261020T030000Z-0badc0de", headers=PHONE).content == b"ZBK1sealed"
    assert c.get("/v1/backup/zeus/..%2Fx", headers=PHONE).status_code == 404


class FakeSessions:
    """A Managed Agents client: each stream yields the scripted events (an exception item raises there)."""

    def __init__(self, *runs):
        self.runs, self.sent = list(runs), []
        self.log, self.status = [], "idle"      # the session's own event log and state, for a resumed run
        self.beta = self
        self.sessions = self
        self.events = self

    def create(self, **k):
        from types import SimpleNamespace as E
        return E(id="sess1")

    def retrieve(self, session_id):
        from types import SimpleNamespace as E
        return E(usage=E(list_cost=None), status=self.status)

    def list(self, session_id, types, created_at_gte):
        return self.log

    def send(self, session_id, events):
        from types import SimpleNamespace as E
        self.sent += events
        return E(data=[E(id=f"ev{len(self.sent)}")])

    def stream(self, session_id):
        import contextlib
        evs = self.runs.pop(0)

        def gen():
            for e in evs:
                if isinstance(e, Exception):
                    raise e
                yield e
        return contextlib.nullcontext(gen())


def said(text):
    from types import SimpleNamespace as E
    return [E(type="agent.message", content=[E(type="text", text=text)]), E(type="session.status_idle", stop_reason=E(type="end_turn"))]


def brain_with(store, client):
    import queue
    from apollo import config
    from apollo.brain import Brain
    config.BRAIN.write_text(json.dumps({"agent": "a", "environment": "e", "vault": "v"}))
    b = Brain.__new__(Brain)
    b.store, b.bus, b.client, b.q = store, FakeBus(), client, queue.Queue()
    b.bq = b.q
    return b


def test_a_fact_reaches_athena_in_a_run(tools):
    c = FakeSessions(said("PASS"))
    b = brain_with(tools.store, c)
    tools.store.add_fact("car", 1, {"connected": True}, False)
    b.deliver_facts(urgent_only=False)
    b._run(b.q.get_nowait())
    assert '"connected": true' in c.sent[0]["content"][0]["text"]
    assert not tools.store.undelivered("athena")


def test_old_unaddressed_facts_and_routines_are_recovered():
    path = ROOT / f"t{os.urandom(4).hex()}.db"
    s = Store(path)
    t, r = s.facts_thread(), s.thread("Routines", "routines")
    old = [s.post(t, "apollo", "HEARTBEAT 1"), s.post(t, "apollo", "FACTS x"), s.post(r, "apollo", "ROUTINE DUE"), s.post(t, "apollo", "HEARTBEAT 2")]
    assert not s.undelivered("athena")
    assert [m["id"] for m in Store(path).undelivered("athena")] == old[1:]


def test_a_broken_stream_resumes_from_the_session_without_sending_again(tools):
    import httpx2
    from types import SimpleNamespace as E
    c = FakeSessions([httpx2.ReadError("gone")])
    c.log = [E(id="ev1", type="user.message"), E(id="m1", type="agent.message", content=[E(type="text", text="On it.")])]
    b = brain_with(tools.store, c)
    t = tools.store.thread("Chat")
    tools.store.post(t, "owner", "hi", "athena")
    c.runs.append([])
    b._run(t)
    assert len(c.sent) == 1 and tools.store.messages(t)[-1]["text"] == "On it."
    assert [o["data"]["agent"] for o in tools.store.outbox(0) if o["kind"] == "message"] == ["athena"]
    assert not tools.store.undelivered("athena") and not tools.store.setting("athena_inflight")


def test_a_run_that_breaks_twice_stays_pending_and_is_resumed_later(tools):
    import httpx2
    c = FakeSessions([httpx2.ReadError("gone")], [httpx2.ReadError("gone")])
    c.status = "running"
    b = brain_with(tools.store, c)
    t = tools.store.thread("Chat")
    tools.store.post(t, "owner", "hi", "athena")
    with pytest.raises(httpx2.ReadError):
        b._run(t)
    assert tools.store.undelivered("athena") and tools.store.setting("athena_inflight")[str(t)]["event"] == "ev1"
    c.runs.append(said("Back."))
    b._run(t)
    assert len(c.sent) == 1 and tools.store.messages(t)[-1]["text"] == "Back."
    assert not tools.store.undelivered("athena")


def _old_session(store, t, mark):
    store.x("UPDATE threads SET session = 'old', vault = 'v' WHERE id = ?", (t,))
    store.set_setting("athena_sessions", {str(t): mark})
    return store.q("SELECT * FROM threads WHERE id = ?", (t,))[0]


def test_sessions_wear_out_by_hours_turns_and_messages(tools):
    import time as _t
    b = brain_with(tools.store, FakeSessions())
    f, t = tools.store.facts_thread(), tools.store.thread("Chat")
    assert b._worn(_old_session(tools.store, f, {"at": _t.time() - 7 * 3600, "turns": 1, "from": 0}))
    assert b._worn(_old_session(tools.store, f, {"at": _t.time(), "turns": 60, "from": 0}))
    assert not b._worn(_old_session(tools.store, f, {"at": _t.time(), "turns": 59, "from": 0}))
    ids = [tools.store.post(t, "owner", f"m{i}") for i in range(151)]
    assert b._worn(_old_session(tools.store, t, {"at": 0, "turns": 0, "from": 0}))
    assert not b._worn(_old_session(tools.store, t, {"at": 0, "turns": 0, "from": ids[0]}))


def test_a_rolled_session_opens_with_the_old_ones_handoff(tools):
    c = FakeSessions(said("Promised to book the dentist."), said("Hi."))
    b = brain_with(tools.store, c)
    t = tools.store.thread("Chat")
    tools.store.set_setting("chat_roll_messages", 1)
    _old_session(tools.store, t, {"at": 0, "turns": 3, "from": 0})
    tools.store.post(t, "owner", "one", "athena")
    tools.store.post(t, "owner", "two", "athena")
    b._run(t)
    assert c.sent[0]["content"][0]["text"] == b.HANDOFF_ASK
    assert c.sent[1]["content"][0]["text"].startswith("[apollo] Handoff from your previous session in this thread:\nPromised to book the dentist.\n\n[owner] one")
    assert tools.store.q("SELECT session FROM threads WHERE id = ?", (t,))[0]["session"] == "sess1"
    assert tools.store.q("SELECT sender FROM messages WHERE source = 'handoff'") == [{"sender": "apollo"}]
    assert not tools.store.undelivered("athena") and tools.store.setting("athena_sessions")[str(t)]["turns"] == 1
    assert len(tools.store.messages(t)) == 4      # nothing lost: his two, the handoff, her reply


def test_a_silent_old_session_gets_apollos_note_instead(tools):
    import threading
    c = FakeSessions([RuntimeError("gone")], said("Ok."))
    b = brain_with(tools.store, c)
    f = tools.store.facts_thread()
    said_before = tools.store.post(f, "athena", "Noted the dentist call.")
    _old_session(tools.store, f, {"at": 0, "turns": 0, "from": 0})
    tools.store.post(f, "apollo", "HEARTBEAT now", "athena")
    b._run(f)
    assert {"type": "user.interrupt"} in c.sent           # its handoff failed: stopped before the new session starts
    text = [s for s in c.sent if s.get("type") == "user.message"][1]["content"][0]["text"]
    assert "didn't answer in time" in text and "[athena] Noted the dentist call." in text and text.count("HEARTBEAT now") == 1
    # A session that never answers: the note doesn't wait past HANDOFF_SECONDS.
    hang = threading.Event()
    c.runs += [iter(lambda: hang.wait(5) and None, 1), said("Ok.")]
    b.HANDOFF_SECONDS = b.HANDOFF_JOIN_SECONDS = 0.2
    _old_session(tools.store, f, {"at": 0, "turns": 0, "from": said_before})
    tools.store.post(f, "apollo", "HEARTBEAT later", "athena")
    b._run(f)
    hang.set()
    assert "didn't answer in time" in c.sent[-1]["content"][0]["text"]


def test_triggers_name_each_facts_state():
    from apollo.routines import matches
    on = lambda trig, kind, data: matches({"on_off": 1, "when_trigger": trig}, kind, data)   # noqa: E731
    assert on("car:connected", "car", {"connected": True, "at": 1}) and on("car:disconnected", "car", {"connected": False})
    assert not on("car:connected", "car", {"connected": False})
    assert on("sleep:asleep", "sleep", {"asleep_at": 1, "seen_at": 2}) and not on("sleep:awake", "sleep", {"asleep_at": 1})
    awake = {"state": "awake", "slept_at": 1, "woke_at": 2, "seen_at": 3}
    assert on("sleep:awake", "sleep", awake) and not on("sleep:asleep", "sleep", awake)
    assert on("sleep:asleep", "sleep", {"state": "asleep", "asleep_at": 1})
    assert on("alarm:dismissed", "alarm", {"state": "dismissed", "at": 1}) and not on("alarm:dismissed", "alarm", {"state": "snoozed"})
    assert on("place:arrive:home", "place", {"event": "arrive", "name": "Home", "id": 3})
    assert not on("place:leave:home", "place", {"event": "arrive", "name": "Home"})
    assert on("wifi:joined", "wifi", {"state": "joined", "ssid": None}) and on("nfc:desk", "nfc", {"tag": "desk"})
    assert on("weather_alert:severe", "weather_alert", {"severity": "Severe", "references": []})


def test_a_bad_time_zone_stops_only_its_row(tools):
    import datetime as dt
    from apollo import routines

    class B:
        def enqueue(self, t): pass
    c = routines.Clock.__new__(routines.Clock)
    c.store, c.brain = tools.store, B()
    fired = []
    c._rows = lambda: [{"id": 1, "on_off": 1, "when_time": "07:00", "tz": "Mars/Base"}, {"id": 2, "on_off": 1, "when_time": "07:00", "tz": "UTC"}]
    c.fire = lambda r, why: fired.append(r["id"])
    c.tick(dt.datetime(2026, 10, 26, 7, 0, tzinfo=dt.timezone.utc))
    assert fired == [2] and "Mars/Base" in tools.store.statuses()[-1]["detail"]


def test_a_long_thread_opens_on_its_newest_page_and_pages_back():
    from fastapi.testclient import TestClient
    from apollo import main
    t = main.store.thread("Long")
    ids = [main.store.post(t, "owner", f"m{i}") for i in range(250)]
    main.store.react(ids[-1], "athena", "+1")
    c = TestClient(main.app)
    page = c.get(f"/v1/threads/{t}", headers=PHONE).json()
    assert [m["id"] for m in page] == ids[50:] and page[-1]["reactions"] == [{"who": "athena", "emoji": "+1"}]
    assert [m["id"] for m in c.get(f"/v1/threads/{t}?before={page[0]['id']}", headers=PHONE).json()] == ids[:50]
    assert [m["id"] for m in c.get(f"/v1/threads/{t}?since={ids[-3]}", headers=PHONE).json()] == ids[-2:]


def test_a_dead_agent_socket_is_dropped_from_its_channel_key(tools):
    from apollo.agents import Bus

    class Dead:
        def sendall(self, b): raise OSError("gone")
    b = Bus(tools.store)
    b.conns[("iris", "dm")] = Dead()
    t = tools.store.thread("Iris", agent="iris")
    b._push("iris", tools.store.post(t, "owner", "hi", "iris"))
    assert b.conns == {}


def test_the_manifest_hashes_only_files_that_changed(monkeypatch):
    seed.plant()
    apps.phone_manifest()
    read = []
    real = apps.Path.read_bytes
    monkeypatch.setattr(apps.Path, "read_bytes", lambda p: read.append(p.name) or real(p))
    apps.sql("drives", "INSERT INTO trips(start, end, miles) VALUES(1, 2, 3)", phone=True)
    apps.phone_manifest()
    assert read == ["data.db"]


def test_a_failed_fcm_token_is_a_status_not_a_crash(tools, monkeypatch, tmp_path):
    from apollo import config, fcm
    acct = tmp_path / "fcm.json"
    acct.write_text(json.dumps({"project_id": "p"}))
    monkeypatch.setattr(config, "FCM_ACCOUNT", acct)
    monkeypatch.setattr(fcm, "should_wake", lambda *a: True)
    monkeypatch.setattr(fcm, "_bearer", lambda sa: (_ for _ in ()).throw(OSError("no network")))
    fcm.wake(tools.store, "card")
    assert {s["component"]: s["detail"] for s in tools.store.statuses()}["fcm"] == "token: OSError"


def test_unlocks_keep_the_unlock_time_not_the_upload_time():
    seed.plant()
    seed.ingest("unlock", 9_000, {"at": 1_234})
    assert apps.sql("routines", "SELECT at FROM unlocks ORDER BY id DESC LIMIT 1")[0]["at"] == 1_234
