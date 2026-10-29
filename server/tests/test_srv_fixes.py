"""Server parity fixes (docs/PARITY-2026-10-27.md): Home's weather, NWS without saved places, app restore, card edits."""
import json

from test_server import ROOT, tools  # noqa: F401  (env and the tools fixture)

from apollo import apps, nws, seed, weathernow


def test_weather_now_is_written_where_he_is_and_bumps_only_on_change(tools):
    seed.plant()
    asked = []
    get = lambda lat, lng: asked.append((lat, lng)) or {"current": {"temperature_2m": 41.3, "weather_code": 63}}
    assert weathernow.update(tools.store, get)
    assert asked[-1] == (nws.BOSTON["lat"], nws.BOSTON["lng"])      # nothing known yet: Boston
    now = json.loads((apps.root() / "weather" / "now.json").read_text())
    assert (now["kind"], now["temp"], now["summary"]) == ("rain", 41.3, "Rain")
    v = apps.version()
    assert not weathernow.update(tools.store, get) and apps.version() == v    # same sky: no new version
    tools.store.add_fact("trip", 1, {"points": [[40.71, -74.0, 1, 3.0]]}, False)
    assert weathernow.update(tools.store, lambda lat, lng: asked.append((lat, lng)) or {"current": {"weather_code": 73}})
    assert asked[-1] == (40.71, -74.0) and apps.version() == v + 1
    assert json.loads((apps.root() / "weather" / "now.json").read_text())["kind"] == "snow"
    assert [weathernow.kind(c) for c in (0, 3, 45, 61, 95, 86, None)] == ["clear", "cloudy", "cloudy", "rain", "rain", "snow", "clear"]


def test_nws_without_saved_places_checks_his_last_location_and_boston(tools, monkeypatch):
    monkeypatch.setattr(nws.apps, "sql", lambda *a, **k: [])
    tools.store.add_fact("stay", 1, {"state": "here", "lat": 41.5, "lng": -81.7}, False)
    tools.store.add_fact("stay", 2, {"state": "here", "lat": 19.08, "lng": 72.88}, False)     # India: NWS doesn't cover it
    assert [p["name"] for p in nws.places(tools.store)] == ["Boston"]
    tools.store.add_fact("stay", 3, {"state": "here", "lat": 41.5, "lng": -81.7}, False)
    asked = []
    nws.check(tools.store, None, lambda lat, lng: asked.append((lat, lng)) or {"features": []})
    assert asked == [(41.5, -81.7), (42.386, -71.078)]


def test_phone_do_names_every_handler_and_converts_their_times(tools):
    from apollo.tools import TOOLS, when_ms
    desc = next(t for t in TOOLS if t["name"] == "phone_do")["description"]
    assert all(h in desc for h in ("live {", "live_stop", "plug {", "home_state", "add_his_calendar"))
    tools.run("athena", "phone_do", {"action": "live", "args": {"title": "Bills", "until": "2026-10-30T18:00"}, "why": "w"})
    assert tools.store.outbox(0)[-1]["data"]["args"]["until"] == when_ms("2026-10-30T18:00")
    tools.run("athena", "phone_do", {"action": "add_his_calendar", "args": {"title": "x", "start": "2026-10-30T18:00"}, "why": "w"})
    assert tools.store.outbox(0)[-1]["data"]["args"]["start"] == when_ms("2026-10-30T18:00")


def test_archived_apps_can_be_listed_and_restored_by_athena_and_his_tap(tools):
    from fastapi.testclient import TestClient
    from apollo import main
    from test_server import PHONE
    tools.run("athena", "app_create", {"name": "keepme", "title": "Keep", "about": "a", "why": "w"})
    tools.run("athena", "app_archive", {"name": "keepme", "why": "w"})
    assert "keepme" in [a["name"] for a in json.loads(tools.run("athena", "app_restore", {}))]
    assert "Not done" in tools.run("athena", "app_restore", {"name": "keepme"}, facts_turn=True)
    assert "Restored" in tools.run("athena", "app_restore", {"name": "keepme"})
    assert tools.store.outbox(0)[-1]["kind"] == "apps"
    tools.run("athena", "app_archive", {"name": "keepme", "why": "w"})
    c = TestClient(main.app)
    assert c.get("/v1/apps/archived").status_code == 401
    assert "keepme" in [a["name"] for a in c.get("/v1/apps/archived", headers=PHONE).json()]
    assert c.post("/v1/apps/keepme/restore", headers=PHONE).json()["ok"]
    assert "keepme" in [a["name"] for a in apps.list_apps()]
    assert c.post("/v1/apps/keepme/restore", headers=PHONE).status_code == 404
    assert c.post("/v1/apps/..%2Fx/restore", headers=PHONE).status_code == 404


def test_cards_can_be_changed_removed_and_listed(tools):
    out = tools.run("athena", "card", {"title": "Flight", "body": "DL 412"})
    cid = out.split()[1]
    tools.run("iris", "card", {"title": "Iris's", "body": "x"})
    assert "changed" in tools.run("athena", "card", {"id": cid, "title": "Flight", "body": "DL 412, gate B4", "until": 9e12})
    o = tools.store.outbox(0)[-1]
    assert o["kind"] == "card" and o["data"]["id"] == cid and o["data"]["body"] == "DL 412, gate B4"
    assert [c["body"] for c in json.loads(tools.run("athena", "card", {"list": True}))] == ["DL 412, gate B4"]
    assert "Not done" in tools.run("iris", "card", {"id": cid, "remove": True})          # not hers
    second = tools.run("athena", "card", {"title": "Bills", "body": "7 PM"}).split()[1]
    tools.store.add_fact("card_feedback", 1, {"card": second, "what": "seen"}, False)     # he swiped it away
    assert [c["id"] for c in json.loads(tools.run("athena", "card", {"list": True}))] == [cid]
    assert "taken off" in tools.run("athena", "card", {"id": cid, "remove": True})
    assert tools.store.outbox(0)[-1] == {"id": tools.store.outbox(0)[-1]["id"], "kind": "card_remove", "data": {"id": cid, "from": "athena"}}
    assert json.loads(tools.run("athena", "card", {"list": True})) == []
    assert "Not done" in tools.run("athena", "card", {"id": cid, "title": "a", "body": "b"})   # removed


def test_his_tap_archives_an_app_and_card_actions_can_carry_a_do(tools):
    from fastapi.testclient import TestClient
    from apollo import main
    from test_server import PHONE
    tools.run("athena", "app_create", {"name": "tapkill", "title": "T", "about": "a", "why": "w"})
    c = TestClient(main.app)
    assert c.post("/v1/apps/tapkill/archive", headers=PHONE).json()["ok"]
    assert "tapkill" in [a["name"] for a in c.get("/v1/apps/archived", headers=PHONE).json()]
    assert c.post("/v1/apps/tapkill/archive", headers=PHONE).status_code == 404
    tools.run("athena", "card", {"title": "Wake", "body": "6:30", "actions": ["ok", {"label": "Set it", "do": "alarm:06:30"}]})
    assert tools.store.outbox(0)[-1]["data"]["actions"][1] == {"label": "Set it", "do": "alarm:06:30"}


def test_weather_now_has_celsius_and_a_place_name(tools):
    seed.plant()
    assert weathernow.update(tools.store, lambda lat, lng: {"current": {"temperature_2m": 50.0, "weather_code": 0}})
    now = json.loads((apps.root() / "weather" / "now.json").read_text())
    assert now["temp_c"] == 10.0 and now["place"]
    asked = []
    rev = lambda store, a: asked.append(a) or {"city": "Boston", "state": "New York"}     # noqa: E731
    spot = {"name": "his last location", "lat": 42.41, "lng": -71.07}
    assert weathernow.place_name(tools.store, spot, rev) == "Boston, New York"
    assert weathernow.place_name(tools.store, spot, rev) == "Boston, New York" and len(asked) == 1   # remembered
    apps.sql("drives", "INSERT INTO places(name, lat, lng) VALUES ('Home', 42.411, -71.071)", phone=True)
    assert weathernow.place_name(tools.store, spot, rev) == "Home"                                    # a saved place first


def test_iris_and_atlas_plan_windows_reach_the_usage_page(tools, tmp_path):
    from apollo import plans
    seed.plant()
    (tmp_path / "claude.json").write_text(json.dumps({"provider": "claude", "plan": "max", "ok": True, "windows": [
        {"key": "five_hour", "label": "5h", "used_pct": 50.0, "resets_at": 1793170000},
        {"key": "seven_day", "label": "7d", "used_pct": 69.0, "resets_at": 1793500000}]}))
    (tmp_path / "codex.json").write_text(json.dumps({"provider": "codex", "plan": "prolite", "ok": True, "windows": [
        {"key": "primary", "label": "7d", "used_pct": 30.0, "resets_at": 1793600000000}]}))
    assert plans.update(tmp_path) == 3
    rows = {(r["who"], r["meter"]): r for r in apps.sql("usage", "SELECT who, meter, used_pct, resets_at FROM usage")}
    assert rows[("iris", "claude_5h")]["used_pct"] == 50.0 and rows[("iris", "claude_5h")]["resets_at"] == 1793170000000
    assert rows[("atlas", "codex_week")]["resets_at"] == 1793600000000
    assert plans.update(tmp_path) == 0                       # unchanged: no new rows
    assert plans.update(tmp_path / "nowhere") == 0           # no snapshots yet: nothing, no error
