import contextlib
import threading

from test_server import FakeSessions, _old_session, brain_with, said, tools  # noqa: F401  (the tools fixture)


class Blocked:
    """A stream that yields nothing until it's closed."""

    def __init__(self):
        self.closed = threading.Event()

    def __iter__(self):
        self.closed.wait(5)
        raise RuntimeError("closed")

    def close(self):
        self.closed.set()


def test_a_handoff_out_of_time_stops_the_old_session_before_the_new_one(tools):
    c = FakeSessions(said("Ok."))
    old = Blocked()
    c.runs.insert(0, old)
    stream = c.stream
    c.stream = lambda session_id: contextlib.nullcontext(c.runs.pop(0)) if c.runs and c.runs[0] is old else stream(session_id)
    b = brain_with(tools.store, c)
    b.HANDOFF_SECONDS = 0.2
    f = tools.store.facts_thread()
    _old_session(tools.store, f, {"at": 0, "turns": 0, "from": 0})
    tools.store.post(f, "apollo", "HEARTBEAT now", "athena")
    b._run(f)
    assert old.closed.is_set()
    kinds = [e["type"] for e in c.sent]
    assert kinds == ["user.message", "user.interrupt", "user.message"]      # the ask, the stop, then the new session's turn
    assert "didn't answer in time" in c.sent[-1]["content"][0]["text"]


def stuck(hang):
    """A run that goes idle waiting on a tool nobody answers (her box is down), then nothing."""
    from types import SimpleNamespace as E
    yield E(type="session.status_idle", stop_reason=E(type="requires_action", event_ids=["t1"]))
    hang.wait(5)


def test_a_stalled_run_is_retired_and_sent_once_more_in_a_fresh_session(tools):
    hang = threading.Event()
    c = FakeSessions(stuck(hang), said("Found it."))
    b = brain_with(tools.store, c)
    tools.store.set_setting("run_stall_minutes", 0.005)
    t = tools.store.thread("Chat")
    tools.store.post(t, "owner", "grep my notes", "athena")
    b._run(t)
    hang.set()
    assert [e["type"] for e in c.sent] == ["user.message", "user.interrupt", "user.message"]
    again = c.sent[-1]["content"][0]["text"]
    assert "didn't answer in time" in again and again.count("grep my notes") == 1
    assert tools.store.messages(t)[-1]["text"] == "Found it." and not tools.store.undelivered("athena")
    assert not tools.store.setting("athena_inflight") and not tools.store.setting("athena_sessions")[str(t)].get("stalled")


def test_a_second_stall_says_so_in_the_thread_and_on_the_status_page(tools):
    hang = threading.Event()
    c = FakeSessions(stuck(hang), stuck(hang))
    b = brain_with(tools.store, c)
    tools.store.set_setting("run_stall_minutes", 0.005)
    t = tools.store.thread("Chat")
    tools.store.post(t, "owner", "grep my notes", "athena")
    b._run(t)
    hang.set()
    assert tools.store.messages(t)[-1]["text"] == b.STALL_LINE
    assert [o["data"]["text"] for o in tools.store.outbox(0) if o["kind"] == "message"] == [b.STALL_LINE]
    assert tools.store.q("SELECT ok, detail FROM status WHERE component = 'brain'")[0]["detail"].startswith("stalled")
    assert not tools.store.undelivered("athena") and not tools.store.setting("athena_inflight")
    assert tools.store.setting("athena_sessions")[str(t)]["stalled"]       # his next message opens a fresh session
    c.runs.append(said("Back now."))
    tools.store.post(t, "owner", "still there?", "athena")
    b._run(t)
    assert "[owner] grep my notes" in c.sent[-1]["content"][0]["text"]      # the note carries what went unanswered
    assert tools.store.q("SELECT ok FROM status WHERE component = 'brain'")[0]["ok"] == 1


def test_a_resumed_run_idle_on_an_action_is_not_marked_answered(tools):
    # msg 857: her session sat idle waiting on a tool; the resume took idle for done and marked it delivered, unanswered.
    import httpx2
    from types import SimpleNamespace as E
    c = FakeSessions([httpx2.ReadError("gone")])
    c.log = [E(id="ev1", type="user.message"), E(id="i1", type="session.status_idle", stop_reason=E(type="requires_action", event_ids=["t1"]))]
    hang = threading.Event()
    c.runs += [stuck(hang), stuck(hang)]       # the resume's stream, then the fresh session's
    b = brain_with(tools.store, c)
    tools.store.set_setting("run_stall_minutes", 0.005)
    t = tools.store.thread("Chat")
    tools.store.post(t, "owner", "hi", "athena")
    b._run(t)
    hang.set()
    assert tools.store.messages(t)[-1]["text"] == b.STALL_LINE
    assert [e["type"] for e in c.sent] == ["user.message", "user.interrupt"] * 2      # never sent twice to the stuck one


def out(store, *kinds):
    return [(o["kind"], o["data"]) for o in store.outbox(0) if o["kind"] in kinds and o["data"].get("from") == "apollo"]


def test_an_alert_pops_once_per_outage_and_clears_with_a_quiet_line(tools):
    from apollo import alerts
    s = tools.store
    assert alerts.raise_(s, "tool:x", "x broke", "key sk-ant-abcdefghijkl leaked?") and not alerts.raise_(s, "tool:x", "x broke", "again")
    (_, pop), (_, card) = out(s, "notify", "card")
    assert pop["priority"] == "high" and "sk-ant" not in pop["body"] and card["id"] == "alert:tool:x"
    live = s.setting("alerts_live")
    live["tool:x"]["popped"] -= 3 * 3600        # still broken hours later: it pops again
    s.set_setting("alerts_live", live)
    assert alerts.raise_(s, "tool:x", "x broke", "again")
    alerts.clear(s, "tool:x")
    alerts.clear(s, "tool:x")
    tail = out(s, "notify", "card", "card_remove")[-3:]
    assert tail[0] == ("card_remove", {"id": "alert:tool:x", "from": "apollo"}) and "cancel" in tail[1][1]
    assert tail[2][1]["title"] == "Fixed: x broke" and tail[2][1]["priority"] == "low" and len(out(s, "notify")) == 4


def test_status_rows_alert_at_once_or_after_their_grace(tools):
    import time as _t
    from apollo import alerts
    s = tools.store
    s.set_status("routines", False, "routine #3: ValueError")
    assert [d["title"] for _, d in out(s, "notify")] == ["routines is down"]
    s.set_status("iris-dm", False, "disconnected")
    s.set_status("atlas-dm", False, "disconnected")
    alerts.sweep(s, _t.time() + 60)
    assert len(out(s, "notify")) == 1      # a blink waits
    s.set_status("atlas-dm", True, "connected")
    alerts.sweep(s, _t.time() + 11 * 60)
    assert [d["title"] for _, d in out(s, "notify")] == ["routines is down", "iris-dm is down"]
    s.set_status("iris-dm", True, "connected")
    assert ("card_remove", {"id": "alert:status:iris-dm", "from": "apollo"}) in out(s, "card_remove")
    s.set_status("brain", False, "iris: the credit ran out", alert=False)
    assert "status:brain" not in (s.setting("alerts_live") or {})


def test_a_heartbeat_with_no_answer_alerts_until_she_answers_there(tools):
    hang = threading.Event()
    c = FakeSessions(stuck(hang), stuck(hang), said("PASS"))
    b = brain_with(tools.store, c)
    tools.store.set_setting("run_stall_minutes", 0.005)
    f = tools.store.facts_thread()
    tools.store.post(f, "apollo", "HEARTBEAT now", "athena")
    b._run(f)
    hang.set()
    (_, pop), (_, card) = out(tools.store, "notify", "card")
    assert pop["priority"] == "high" and "heartbeat" in pop["body"] and card["id"] == f"alert:stall:{f}"
    assert pop["open"] == f"thread:{f}" and card["actions"] == [{"label": "Open", "do": f"thread:{f}"}]
    tools.store.post(f, "apollo", "HEARTBEAT later", "athena")
    b._run(f)
    assert ("card_remove", {"id": f"alert:stall:{f}", "from": "apollo"}) in out(tools.store, "card_remove")


def test_a_tool_failing_three_times_in_an_hour_alerts_and_its_success_clears(tools, monkeypatch):
    def boom(who, a):
        raise OSError("disk")
    monkeypatch.setattr(tools, "t_facts", boom)
    for _ in range(3):
        assert "Not done" in tools.run("athena", "facts", {})
    assert [d["title"] for _, d in out(tools.store, "notify")] == ["The facts tool keeps failing"]
    monkeypatch.setattr(tools, "t_facts", lambda who, a: [])
    tools.run("athena", "facts", {})
    assert out(tools.store, "card_remove")


def test_a_server_error_alerts_once_per_route_and_clears_on_its_next_good_answer():
    from fastapi.testclient import TestClient
    from apollo import main
    bad = [True]

    @main.app.get("/v1/test-boom/{n}")
    def boom(n: int):
        if bad[0]:
            raise RuntimeError("x")
        return {"ok": True}
    c = TestClient(main.app)
    assert c.get("/v1/test-boom/1").status_code == 500 and c.get("/v1/test-boom/2").status_code == 500
    pops = [d for _, d in out(main.store, "notify") if d["title"] == "Apollo server error"]
    assert len(pops) == 1 and "/v1/test-boom/{n}" in pops[0]["body"]
    bad[0] = False
    assert c.get("/v1/test-boom/3").status_code == 200
    assert ("card_remove", {"id": "alert:server:/v1/test-boom/{n}", "from": "apollo"}) in out(main.store, "card_remove")


def test_the_box_status_file_drives_the_box_row(tools, monkeypatch, tmp_path):
    import os
    import time as _t
    from apollo import alerts, config
    p = tmp_path / "status"
    monkeypatch.setattr(config, "BOX_STATUS", p)
    alerts.box(tools.store)
    assert not tools.store.q("SELECT 1 FROM status WHERE component = 'box'")       # not set up: nothing
    p.write_text("apollo-box true\napollo-doorman false\n")
    alerts.box(tools.store)
    assert tools.store.q("SELECT ok, detail FROM status WHERE component = 'box'")[0]["detail"] == "not running: apollo-doorman"
    p.write_text("apollo-box true\napollo-doorman true\n")
    os.utime(p, (_t.time() - 600, _t.time() - 600))
    alerts.box(tools.store)
    assert "not updated" in tools.store.q("SELECT detail FROM status WHERE component = 'box'")[0]["detail"]


def test_a_stalled_retry_is_told_what_the_stuck_try_already_did(tools):
    from types import SimpleNamespace as E
    hang = threading.Event()

    def half_done():
        yield E(type="agent.message", content=[E(type="text", text="Sending it now.")])
        tools.store.log("athena", 'notify: {"title": "Dentist at 3"}')
        yield E(type="agent.mcp_tool_use", id="tu1", name="notify", input={"title": "Dentist at 3"}, mcp_server_name="apollo")
        yield E(type="agent.mcp_tool_result", id="r1", mcp_tool_use_id="tu1")
        hang.wait(5)
    c = FakeSessions(half_done(), said("Done."))
    c.log = [E(id="ev1", type="user.message"), E(type="agent.mcp_tool_use", id="tu1", name="notify", input={"title": "Dentist at 3"}),
             E(type="agent.mcp_tool_result", id="r1", mcp_tool_use_id="tu1")]
    b = brain_with(tools.store, c)
    tools.store.set_setting("run_stall_minutes", 0.005)
    t = tools.store.thread("Chat")
    tools.store.post(t, "owner", "remind me about the dentist", "athena")
    b._run(t)
    hang.set()
    again = c.sent[-1]["content"][0]["text"]
    assert "Already done in the stalled attempt (don't repeat):" in again
    assert '- notify {"title": "Dentist at 3"} (done)' in again and "Apollo's log: notify" in again
    assert tools.store.messages(t)[-1]["text"] == "Done."       # the abandoned try's words don't ride along


def test_a_handoff_whose_stream_opens_late_never_reaches_the_old_session(tools):
    import time as _t
    c = FakeSessions(said("Ok."))
    stopped, send, stream = threading.Event(), c.send, c.stream

    def late(session_id):       # the old session's stream opens only after the handoff ran out of time
        if session_id != "old":
            return stream(session_id)
        stopped.wait(5)
        return contextlib.nullcontext(iter(()))

    def sending(session_id, events):
        if events[0]["type"] == "user.interrupt":
            stopped.set()
        return send(session_id, events)
    c.stream, c.send = late, sending
    b = brain_with(tools.store, c)
    b.HANDOFF_SECONDS = 0.2
    t = tools.store.thread("Chat")
    _old_session(tools.store, t, {"at": 0, "turns": 0, "from": 0, "stalled": False})
    tools.store.set_setting("chat_roll_messages", 0)
    tools.store.post(t, "owner", "hi", "athena")
    b._run(t)
    _t.sleep(0.3)
    assert [e["type"] for e in c.sent] == ["user.interrupt", "user.message"]
    assert b.HANDOFF_ASK not in [e.get("content", [{}])[0].get("text") for e in c.sent]


def test_an_interrupt_that_fails_is_logged_and_alerts_when_it_repeats(tools):
    c = FakeSessions()
    send = c.send

    def failing(session_id, events):
        if fail[0]:
            raise ConnectionError("down")
        return send(session_id, events)
    fail, c.send = [True], failing
    b = brain_with(tools.store, c)
    b._interrupt("s1")
    assert tools.store.q("SELECT what FROM log WHERE what LIKE 'athena interrupt failed%'") and "interrupt" not in (tools.store.setting("alerts_live") or {})
    b._interrupt("s1")
    assert "interrupt" in tools.store.setting("alerts_live")
    fail[0] = False
    b._interrupt("s1")
    assert "interrupt" not in tools.store.setting("alerts_live")


def test_a_server_error_alert_stays_up_through_a_401_and_clears_on_a_success():
    from fastapi import HTTPException
    from fastapi.testclient import TestClient
    from apollo import main
    state = ["boom"]

    @main.app.get("/v1/test-auth-boom")
    def boom():
        if state[0] == "boom":
            raise RuntimeError("x")
        if state[0] == "401":
            raise HTTPException(401)
        return {"ok": True}
    c = TestClient(main.app)
    key = "alert:server:/v1/test-auth-boom"
    assert c.get("/v1/test-auth-boom").status_code == 500
    state[0] = "401"
    assert c.get("/v1/test-auth-boom").status_code == 401
    assert ("card_remove", {"id": key, "from": "apollo"}) not in out(main.store, "card_remove")
    state[0] = "ok"
    assert c.get("/v1/test-auth-boom").status_code == 200
    assert ("card_remove", {"id": key, "from": "apollo"}) in out(main.store, "card_remove")
