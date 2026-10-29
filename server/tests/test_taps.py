"""His own taps go to his "Your taps" thread, a trusted session like a chat, never the facts turn (2026-10-28)."""
import json
import time

from test_server import PHONE, FakeSessions, brain_with, tools  # noqa: F401  (env and the tools fixture)


def post(monkeypatch, facts):
    from fastapi.testclient import TestClient
    from apollo import main
    woke, runs = [], []
    monkeypatch.setattr(main.brain, "facts_now", lambda: woke.append(1))
    monkeypatch.setattr(main.brain, "enqueue", runs.append)
    main.store.set_setting("facts_bundle_seconds", 0.01)
    assert TestClient(main.app).post("/v1/facts", headers=PHONE, json={"facts": facts}).status_code == 200
    for _ in range(100):
        if runs or not any(f["kind"] in ("page_write", "card_feedback") for f in facts):
            break
        time.sleep(0.01)
    return main.store, woke, runs


def test_his_page_write_goes_to_his_taps_thread_not_the_facts_turn(monkeypatch):
    s, woke, runs = post(monkeypatch, [{"kind": "page_write", "at": 1, "id": "t:1",
                                        "data": {"app": "theogony", "sql": "UPDATE answers SET a = ?", "params": ["Zeus"], "gesture": True}}])
    t = s.taps_thread()
    last = s.messages(t)[-1]["text"]
    assert last.startswith("HIS TAP") and "theogony" in last and "UPDATE answers" in last and "Zeus" in last
    assert runs == [t] and woke == [] and not [r for r in s.unsent_facts() if r["kind"] == "page_write"]


def test_an_alert_cards_tap_and_a_notification_stay_out_of_his_taps(monkeypatch):
    s, woke, _ = post(monkeypatch, [{"kind": "card_feedback", "at": 2, "id": "t:2", "data": {"card": "alert:stall:9", "what": "Retry"}},
                                    {"kind": "notification", "at": 3, "id": "t:3", "data": {"pkg": "com.whatsapp", "title": "Amma", "text": "hi"}}])
    assert not any("alert:stall" in m["text"] for m in s.messages(s.taps_thread()))
    assert [r["kind"] for r in s.unsent_facts()][-1:] == ["notification"] and woke == [1]
    s, _, runs = post(monkeypatch, [{"kind": "card_feedback", "at": 4, "id": "t:4", "data": {"card": "c7", "what": "Yes"}}])
    assert "card_feedback in «c7»" in s.messages(s.taps_thread())[-1]["text"] and runs == [s.taps_thread()]


def test_an_action_result_goes_back_to_the_session_that_asked(monkeypatch):
    from fastapi.testclient import TestClient
    from apollo import main
    woke, taps = [], []
    monkeypatch.setattr(main.brain, "facts_now", lambda: woke.append(1))
    monkeypatch.setattr(main.brain, "tap", lambda fid, kind, at, d: taps.append(d))
    mine = main.store.send("action", {"action": "sms", "args": {}, "why": "he asked", "from": "athena"})
    facts = main.store.send("action", {"action": "sms", "args": {}, "why": "«text»", "from": "athena", "facts_turn": True})
    c = TestClient(main.app)
    c.post(f"/v1/actions/{mine}", headers=PHONE, json={"ok": True, "detail": "sent"})
    c.post(f"/v1/actions/{facts}", headers=PHONE, json={"ok": True, "detail": "sent"})
    assert [d["why"] for d in taps] == ["he asked"] and woke == [1]


def test_the_taps_session_is_trusted_like_a_chat(tools):
    from apollo import config
    made = []
    c = FakeSessions()
    c.create = lambda **k: made.append(k) or __import__("types").SimpleNamespace(id=f"s{len(made)}")
    b = brain_with(tools.store, c)
    config.BRAIN.write_text(json.dumps({"agent": "a", "environment": "e", "vault": "v", "vault_trusted": "vt", "memory_store": "m"}))
    b._session(tools.store.q("SELECT * FROM threads WHERE id = ?", (tools.store.taps_thread(),))[0])
    assert made[0]["resources"][0]["access"] == "read_write" and made[0]["agent"] == "a" and made[0]["vault_ids"] == ["vt"]
