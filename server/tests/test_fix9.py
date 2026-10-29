import threading
from types import SimpleNamespace as E

from test_server import FakeSessions, brain_with, said, tools  # noqa: F401  (the tools fixture)


def started(hang, name, args):
    """A run that calls a tool and then hangs before any result comes back."""
    yield E(type="agent.mcp_tool_use", id="tu1", name=name, input=args, mcp_server_name="apollo")
    hang.wait(5)


def stalled_on(tools, name, args):
    hang = threading.Event()
    c = FakeSessions(started(hang, name, args), said("Done."))
    c.log = [E(id="ev1", type="user.message"), E(type="agent.mcp_tool_use", id="tu1", name=name, input=args, mcp_server_name="apollo")]
    b = brain_with(tools.store, c)
    tools.store.set_setting("run_stall_minutes", 0.005)
    t = tools.store.thread("Chat")
    tools.store.post(t, "owner", "text Mom I'm late", "athena")
    b._run(t)
    hang.set()
    return b, c, t


def test_a_stall_after_a_write_with_no_result_waits_for_his_retry(tools):
    b, c, t = stalled_on(tools, "phone_do", {"action": "sms", "to": "Mom"})
    assert [e["type"] for e in c.sent] == ["user.message", "user.interrupt"]      # never replayed on its own
    assert tools.store.messages(t)[-1]["text"] == b.UNSURE_LINE and not tools.store.undelivered("athena")
    card = [o["data"] for o in tools.store.outbox(0) if o["kind"] == "card"][-1]
    assert card["id"] == f"alert:stall:{t}" and "Retry" in card["actions"]
    b.card_tap({"card": f"alert:stall:{t}", "what": "Retry"})
    b._run(b.q.get_nowait())
    again = c.sent[-1]["content"][0]["text"]
    assert "phone_do" in again and "no result seen; it may have run" in again and b.RETRY_ASK in again and "text Mom" in again
    assert tools.store.messages(t)[-1]["text"] == "Done." and not tools.store.setting("athena_retry")
    b.card_tap({"card": f"alert:stall:{t}", "what": "Retry"})       # a second tap does nothing
    assert b.q.empty()


def test_a_stall_on_a_read_with_no_result_retries_on_its_own(tools):
    b, c, t = stalled_on(tools, "facts", {"kind": "car"})
    assert [e["type"] for e in c.sent] == ["user.message", "user.interrupt", "user.message"]
    assert tools.store.messages(t)[-1]["text"] == "Done."


def test_only_reads_count_as_safe():
    from apollo.brain import Brain
    r = Brain._reads
    assert r("agent.mcp_tool_use", "app_sql", "apollo", {"sql": "SELECT * FROM t"})
    assert r("agent.mcp_tool_use", "app_sql", "apollo", {"sql": "with x as (select 1) select * from x;"})
    assert not r("agent.mcp_tool_use", "app_sql", "apollo", {"sql": "WITH x AS (SELECT 1) DELETE FROM t"})
    assert not r("agent.mcp_tool_use", "app_sql", "apollo", {"sql": "select 1; drop table t"})
    assert r("agent.tool_use", "grep", None, {}) and not r("agent.tool_use", "bash", None, {"command": "ls"})
    assert not r("agent.mcp_tool_use", "facts", "other", {})       # another server's tool of the same name


def test_a_card_feedback_fact_reaches_the_brain(monkeypatch):
    from apollo import main
    got = []
    monkeypatch.setattr(main.brain, "card_tap", got.append)
    main._fact({"kind": "card_feedback", "data": {"card": "alert:stall:3", "what": "Retry"}})
    assert got == [{"card": "alert:stall:3", "what": "Retry"}]


def handoff_brain(tools, c):
    """An old chat session due for a roll whose handoff ask never answers (its stream ends when closed)."""
    import contextlib
    from test_fix7 import Blocked
    from test_server import _old_session
    old, stream = Blocked(), c.stream
    c.stream = lambda session_id: contextlib.nullcontext(old) if session_id == "old" else stream(session_id)
    b = brain_with(tools.store, c)
    b.HANDOFF_SECONDS = b.STOP_SECONDS = 0.2
    t = tools.store.thread("Chat")
    _old_session(tools.store, t, {"at": 0, "turns": 0, "from": 0})
    tools.store.set_setting("chat_roll_messages", 0)
    tools.store.post(t, "owner", "hi", "athena")
    return b, t


def held(tools, b, t):
    import pytest
    from apollo.brain import NotStopped
    with pytest.raises(NotStopped):
        b._run(t)
    assert tools.store.q("SELECT session FROM threads WHERE id = ?", (t,))[0]["session"] == "old"     # no second session
    assert [r["text"] for r in b._pending(t)] == ["hi"]
    assert tools.store.setting("athena_sessions")[str(t)]["stalled"] and f"stall:{t}" in tools.store.setting("alerts_live")


def test_a_handoff_whose_old_session_keeps_running_starts_no_new_one(tools):
    c = FakeSessions(said("Ok."))
    c.status = "running"        # the ask landed after the stop: she is working on it
    b, t = handoff_brain(tools, c)
    held(tools, b, t)
    assert [e["type"] for e in c.sent] == ["user.message", "user.interrupt"]
    c.status = "idle"           # the next run asks her nothing more, checks she stopped, then the fresh one gets it once
    b._run(t)
    assert [e["type"] for e in c.sent] == ["user.message", "user.interrupt", "user.message"]
    assert c.sent[-1]["content"][0]["text"].count("[owner] hi") == 1 and tools.store.messages(t)[-1]["text"] == "Ok."


def test_a_handoff_whose_stop_fails_starts_no_new_one(tools):
    c = FakeSessions(said("Ok."))
    send = c.send

    def failing(session_id, events):
        if events[0]["type"] == "user.interrupt":
            raise ConnectionError("down")
        return send(session_id, events)
    c.send = failing
    b, t = handoff_brain(tools, c)
    held(tools, b, t)
