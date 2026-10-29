"""The phone UI's server side: Home pins, pinned places, Zeus's trips."""
from test_server import PHONE, ROOT, tools  # noqa: F401  (env and fixtures)

from apollo import apps, seed


def test_home_pins_take_apps_and_their_widgets_only(tools):
    seed.plant()
    assert "now.html" in str(apps.manifest("weather").get("widgets"))
    tools.run("athena", "phone_setting", {"name": "home_pins", "value": "app:dawn, widget:weather/widgets/now.html", "why": "he asked"})
    assert tools.store.outbox(0)[-1]["data"]["value"] == "app:dawn,widget:weather/widgets/now.html"
    tools.run("athena", "phone_setting", {"name": "home_pins", "value": "", "why": "he cleared them"})
    assert tools.store.outbox(0)[-1]["data"]["value"] == ""
    for bad in ("app:nope", "app:dawn,app:dawn", "widget:weather/widgets/gone.html", "widget:nope/x.html", "dawn", "app:" + ",app:dawn" * 13):
        assert "Not done" in tools.run("athena", "phone_setting", {"name": "home_pins", "value": bad, "why": "x"}), bad


def test_place_pin_saves_and_moves_a_place(tools):
    seed.plant()
    seed.ingest("place_pin", 1, {"name": "Gym", "lat": 42.4, "lng": -71.0})
    seed.ingest("place_pin", 2, {"name": "Gym", "lat": 42.5, "lng": -70.9})
    seed.ingest("place_pin", 3, {"name": "", "lat": 1, "lng": 1})
    seed.ingest("place_pin", 4, {"name": "Bad", "lat": "x", "lng": 1})
    assert apps.sql("drives", "SELECT name, lat, lng, kind FROM places WHERE name IN ('Gym', '', 'Bad')") == [{"name": "Gym", "lat": 42.5, "lng": -70.9, "kind": "pinned"}]
    apps.sql("drives", "DELETE FROM places WHERE name = 'Gym'", phone=True)     # the apps folder is shared: NWS tests read places


def test_zeus_trips_come_over_once_with_legs_and_notes(tools):
    import json
    from apollo import zeusimport
    seed.plant()
    trip = {"id": "t1", "place": "Mumbai", "startDate": "2025-12-18", "endDate": "2026-01-05", "status": "PLANNED", "notes": ["id copy"],
            "legs": [{"mode": "FLIGHT", "from": "BUF", "to": "HYD", "departAt": 1_766_000_000_000, "carrier": "Emirates", "number": "EK 202"},
                     {"mode": "DRIVE", "from": "HYD", "to": "Lakeside", "returning": True}],
            "stays": [{"name": "Home", "address": "Midtown", "checkIn": 1_766_100_000_000}], "plans": [{"title": "Wedding", "at": 1_766_200_000_000}]}
    body = json.dumps({"version": 1, "trips": [trip, {"id": "t2", "place": "Nowhere", "status": "DISMISSED"}]}).encode()
    out = zeusimport.run(tools.store, body)
    assert out["added"]["zeus_trips"] == 1 and out["added"]["legs"] == 4
    assert apps.sql("trips", "SELECT name, status, start FROM trips WHERE slug LIKE 'zeus-%'") == [{"name": "Trip to Mumbai", "status": "done", "start": "2025-12-18"}]
    kinds = [(r["kind"], r["title"]) for r in apps.sql("trips", "SELECT kind, title FROM legs WHERE source = 'zeus' ORDER BY id")]
    assert kinds == [("flight", "Emirates EK 202"), ("car", "HYD to Lakeside"), ("stay", "Home"), ("event", "Wedding")]
    assert "id copy" in apps.read("trips", "notes/trips/zeus-t1.md")
    assert "zeus_trips" not in zeusimport.run(tools.store, body)["added"]


def test_an_interrupted_zeus_trip_comes_over_whole_on_retry(tools):
    import json
    import pytest
    from apollo import zeusimport
    seed.plant()
    good = {"mode": "FLIGHT", "from": "BUF", "to": "JFK"}
    trip = {"id": "t3", "place": "Albany", "startDate": "2030-01-01", "status": "IDEA", "legs": [good, {"mode": "FLIGHT", "from": {"bad": 1}}]}
    with pytest.raises(Exception):      # dies on the second leg
        zeusimport.run(tools.store, json.dumps({"version": 1, "trips": [trip]}).encode())
    assert apps.sql("trips", "SELECT 1 FROM trips WHERE slug = 'zeus-t3'") == []
    trip["legs"] = [good, {**good, "from": "JFK", "to": "ALB"}]
    assert zeusimport.run(tools.store, json.dumps({"version": 1, "trips": [trip]}).encode())["added"]["legs"] == 2
    assert len(apps.sql("trips", "SELECT l.id FROM legs l JOIN trips t ON t.id = l.trip WHERE t.slug = 'zeus-t3'")) == 2
