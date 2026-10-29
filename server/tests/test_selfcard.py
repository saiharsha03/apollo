from test_server import tools  # noqa: F401  (the tools fixture)

from apollo import apps, changes


def cards(tools):
    return [o["data"] for o in tools.store.outbox(0) if o["kind"] == "card" and str(o["data"].get("id", "")).startswith("change:")]


def made(tools, name):
    tools.run("athena", "app_create", {"name": name, "title": "T", "about": "a", "why": "w"})
    tools.run("athena", "app_write", {"app": name, "path": "notes/a.md", "text": "old", "why": "w"})


def test_her_own_write_gets_a_card_whose_undo_restores_the_old_text(tools):
    made(tools, "selfa")
    assert not cards(tools)         # answering him: no card
    tools.run("athena", "app_write", {"app": "selfa", "path": "notes/a.md", "text": "new", "why": "tidy"}, own="run:1")
    c = cards(tools)[-1]
    assert c["actions"] == ["Undo"] and "notes/a.md" in c["body"] and "until" not in c
    changes.tap(tools, {"card": c["id"], "what": "Undo"})
    assert apps.read("selfa", "notes/a.md") == "old"
    assert cards(tools)[-1]["title"].startswith("Undone") and cards(tools)[-1]["actions"] == []
    apps.write("selfa", "notes/a.md", "his", "w", "owner")
    changes.tap(tools, {"card": c["id"], "what": "Undo"})      # once only
    assert apps.read("selfa", "notes/a.md") == "his"


def test_one_card_per_run_and_undo_reverts_all_of_it(tools):
    made(tools, "selfb")
    tools.run("athena", "app_write", {"app": "selfb", "path": "notes/a.md", "text": "x", "why": "w"}, own="run:2")
    tools.run("athena", "app_write", {"app": "selfb", "path": "pages/new.html", "text": "<p>", "why": "w"}, own="run:2")
    tools.run("athena", "app_create", {"name": "selfc", "title": "C", "about": "a", "why": "w"}, own="run:2")
    assert len({c["id"] for c in cards(tools)}) == 1 and "3 things" in cards(tools)[-1]["title"]
    changes.tap(tools, {"card": cards(tools)[-1]["id"], "what": "Undo"})
    assert apps.read("selfb", "notes/a.md") == "old" and "pages/new.html" not in apps.files("selfb")
    assert "selfc" in [a["name"] for a in apps.archived()]
    tools.run("athena", "app_write", {"app": "selfb", "path": "notes/a.md", "text": "y", "why": "w"}, own="run:3")
    assert len({c["id"] for c in cards(tools)}) == 2


def test_sql_writes_say_no_undo_and_reads_make_no_card(tools):
    made(tools, "selfd")
    tools.run("athena", "app_sql", {"app": "selfd", "sql": "CREATE TABLE t (x)", "why": "w"})
    tools.run("athena", "app_sql", {"app": "selfd", "sql": "SELECT * FROM t"}, own="run:4")
    assert not cards(tools)
    tools.run("athena", "app_sql", {"app": "selfd", "sql": "INSERT INTO t VALUES (1)", "why": "w"}, own="run:4")
    c = cards(tools)[-1]
    assert "can't undo automatically" in c["body"] and c["actions"] == []


def test_a_setting_undo_puts_the_old_value_back(tools):
    tools.run("athena", "phone_setting", {"name": "watch_idle_min", "value": 20, "why": "w"})
    tools.run("athena", "phone_setting", {"name": "watch_idle_min", "value": 45, "why": "w"}, own="run:5")
    changes.tap(tools, {"card": cards(tools)[-1]["id"], "what": "Undo"})
    last = [o["data"] for o in tools.store.outbox(0) if o["kind"] == "setting"][-1]
    assert last["name"] == "watch_idle_min" and last["value"] == 20


def test_an_undo_tap_is_handled_by_apollo_not_her(monkeypatch, tools):
    from apollo import brain, main
    assert not brain.wakes(tools.store, {"kind": "card_feedback", "data": {"card": "change:1", "what": "Undo"}})
    monkeypatch.setattr(main, "tools", tools)
    main._fact({"kind": "card_feedback", "data": {"card": "change:999", "what": "Undo"}})
    assert not [f for f in main.store.unsent_facts() if f["kind"] == "card_feedback" and f["data"].get("card") == "change:999"]


def test_mcp_calls_during_a_run_on_her_own_make_cards(monkeypatch, tools):
    from apollo import main
    made(tools, "selfe")
    monkeypatch.setattr(main, "tools", tools)
    call = {"id": 1, "method": "tools/call", "params": {"name": "app_write", "arguments": {"app": "selfe", "path": "notes/a.md", "text": "z", "why": "w"}}}
    main._rpc(call, facts_turn=False)
    assert not cards(tools)         # a chat turn
    monkeypatch.setattr(main.brain, "_own", {7: "run:7:1"})
    main._rpc(call, facts_turn=False)
    assert cards(tools)
