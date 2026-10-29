"""Only what matters wakes Athena; the rest waits for the heartbeat, which carries it (Owner, 2026-10-27)."""
from test_server import tools  # noqa: F401  (the tools fixture)

from apollo.brain import Brain, wakes


def note(pkg, title="Amma", text="call me"):
    return {"kind": "notification", "data": {"pkg": pkg, "title": title, "text": text}}


def test_only_people_money_rides_and_his_own_taps_wake_her(tools):
    s = tools.store
    assert wakes(s, note("com.whatsapp")) and wakes(s, note("com.chase.sig.android", "Chase", "Balance alert"))
    assert wakes(s, {"kind": "page_tap", "data": {}}) and wakes(s, {"kind": "car", "data": {}})
    for f in (note("app.morphe.android.youtube"), note("com.android.systemui"), note("com.Slack"),
              note("com.whatsapp", text=""),                                       # "new message" with no words
              note("com.google.android.apps.messaging", "75137", "Your code is 123456"),   # a short code
              {"kind": "app_usage", "data": {}}, {"kind": "unlock", "data": {}}, {"kind": "home", "data": {}}):
        assert not wakes(s, f), f
    s.set_setting("wake_apps", ["com.Slack"])                                       # his to change
    assert wakes(s, note("com.Slack")) and not wakes(s, note("com.whatsapp"))


def test_a_quiet_fact_waits_and_the_heartbeat_carries_it_in_one_turn(tools, monkeypatch):
    from fastapi.testclient import TestClient
    from test_server import PHONE
    from apollo import main
    woke, runs = [], []
    monkeypatch.setattr(main.brain, "facts_now", lambda: woke.append(1))
    c = TestClient(main.app)
    c.post("/v1/facts", headers=PHONE, json={"facts": [{"kind": "app_usage", "at": 1, "data": {"app": "YouTube"}}]})
    assert woke == []
    c.post("/v1/facts", headers=PHONE, json={"facts": [{"kind": "notification", "at": 2, "data": {"pkg": "com.whatsapp", "title": "Amma", "text": "hi"}}]})
    assert woke == [1]
    b = Brain.__new__(Brain)
    b.store = tools.store
    b.enqueue = runs.append
    tools.store.add_fact("unlock", 3, {}, False)
    b.heartbeat()
    t = tools.store.facts_thread()
    last = [m["text"] for m in tools.store.messages(t)[-2:]]
    assert "unlock" in last[0] and last[1].startswith("HEARTBEAT") and runs == [t]     # one turn, facts first



def test_groups_old_calls_page_setup_and_a_quiet_hour_dont_wake_her(tools, monkeypatch):
    import time
    s = tools.store
    group = note("com.whatsapp", "Weekend Rides", "need a ride")
    group["data"]["conversation"] = "Weekend Rides"
    assert not wakes(s, group) and wakes(s, note("com.whatsapp"))
    now = time.time() * 1000
    assert wakes(s, {"kind": "call", "data": {"at": now - 60_000}})
    assert not wakes(s, {"kind": "call", "data": {"at": now - 3 * 86400_000}})       # his call history coming over
    assert not wakes(s, {"kind": "page_write", "data": {"sql": "CREATE TABLE IF NOT EXISTS entries (id)"}})
    assert wakes(s, {"kind": "page_write", "data": {"sql": "UPDATE steps SET done = 1"}})
    b = Brain.__new__(Brain)
    b.store, runs = s, []
    b.enqueue = runs.append
    for r in s.unsent_facts():
        s.mark_sent([r["id"]])
    b.heartbeat()
    assert runs == [s.facts_thread()]                         # the heartbeat comes every hour all the same: her time to act
