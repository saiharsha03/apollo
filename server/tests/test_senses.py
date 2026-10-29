"""Restored senses on the server: calls into routines, Dawn's setup rows, NWS alerts as facts, bank notifications to Iris
and the fraud ring."""
import json

from test_server import FakeBus, ROOT, tools  # noqa: F401  (env and the tools fixture)

from apollo import apps, bank, nws, seed
from apollo.store import Store


def test_calls_keep_their_own_time_and_direction():
    seed.plant()
    seed.ingest("call", 999, {"at": 123, "who": "Amma", "how": "whatsapp video", "dir": "missed", "missed": True, "seconds": None})
    seed.ingest("call", 999, {"at": 124, "who": "+17165550100", "how": "phone", "dir": "outgoing", "missed": False, "seconds": 61})
    rows = apps.sql("routines", "SELECT at, who, how, missed, seconds FROM calls WHERE at IN (123, 124) ORDER BY at")
    assert rows == [{"at": 123, "who": "Amma", "how": "whatsapp video missed", "missed": 1, "seconds": None},
                    {"at": 124, "who": "+17165550100", "how": "phone outgoing", "missed": 0, "seconds": 61}]


def test_dawn_has_steps_for_the_new_senses():
    seed.plant()
    keys = {r["key"] for r in apps.sql("dawn", "SELECT key FROM steps")}
    assert {"call_log", "activity", "health_write", "docs_folders"} <= keys
    seed.ingest("setup", 9, {"key": "perm_read_call_log", "ok": False, "detail": "not allowed"})
    assert apps.sql("dawn", "SELECT ok FROM status WHERE key = 'perm_read_call_log'") == [{"ok": 0}]


ALERT = {"features": [{"properties": {"id": "urn:a1", "event": "Winter Storm Warning", "severity": "Severe", "urgency": "Expected",
                                      "headline": "Winter Storm Warning until 6 PM", "areaDesc": "Erie", "description": "x" * 5000,
                                      "references": []}}]}
UPDATE = {"features": [{"properties": {"id": "urn:a2", "event": "Winter Storm Warning", "severity": "Severe",
                                       "references": [{"identifier": "urn:a1"}]}}]}


def test_nws_alerts_become_facts_once_and_updates_stay_quiet(tools):
    seed.plant()
    apps.sql("drives", "INSERT INTO places(name, lat, lng) VALUES('nws-home', 42.41, -71.01)", phone=True)
    apps.sql("drives", "INSERT INTO places(name, lat, lng) VALUES('nws-india', 19.08, 72.88)", phone=True)   # NWS doesn't cover it
    asked, seen = [], []
    get = lambda lat, lng: asked.append((lat, lng)) or ALERT
    assert nws.check(tools.store, lambda k, d: seen.append(k), get) == 1
    f = tools.store.unsent_facts()[-1]
    assert f["kind"] == "weather_alert" and f["urgent"] and f["data"]["place"] == "nws-home" and len(f["data"]["description"]) == 1200
    assert all(nws.covered(*p) for p in asked) and seen == ["weather_alert"]
    assert nws.check(tools.store, None, get) == 0                       # told already
    assert nws.check(tools.store, None, lambda lat, lng: UPDATE) == 0   # an update of a told alert
    assert tools.store.statuses() and not tools.store.alerts()


def test_nws_failure_is_a_status_not_a_crash(tools):
    seed.plant()
    apps.sql("drives", "INSERT INTO places(name, lat, lng) VALUES('nws-work', 42.45, -71.05)", phone=True)

    def boom(lat, lng):
        raise OSError("down")
    assert nws.check(tools.store, None, boom) == 0
    assert [a["source"] for a in tools.store.alerts()] == ["nws"]


def notice(pkg, title, text, app="Bank"):
    return {"pkg": pkg, "app": app, "title": title, "text": text, "at": 1_700_000_000_000}


def test_bank_notifications_reach_iris_and_fraud_rings_him(tools):
    s, bus = tools.store, FakeBus()
    bank.notification(s, bus, notice("com.chase.sig.android", "Chase", "You made a $42.10 purchase at Wegmans", "Chase"))
    assert bus.sent[-1][0] == "iris" and s.thread_agent(bus.sent[-1][1]) == "iris"
    assert "Wegmans" in s.message(bus.sent[-1][2])["text"] and not s.alerts()
    # A text from a sender he never confirmed is not a bank, whatever it says (a scam text can say anything).
    bank.notification(s, bus, notice("com.google.android.apps.messaging", "33789", "Fraud alert: did you make a $900 charge?"))
    assert len(bus.sent) == 1 and not s.alerts()
    bank.sender_fact(s, {"sender": "33-789", "on": True})
    bank.notification(s, bus, notice("com.google.android.apps.messaging", "33789", "Fraud alert: did you make a $900 charge? Reply Y/N"))
    bank.notification(s, bus, notice("com.google.android.apps.messaging", "33789", "Fraud alert: did you make a $900 charge? Reply Y/N"))
    assert len(bus.sent) == 3
    alerts = s.alerts()
    assert len(alerts) == 1 and alerts[0]["level"] == "high" and "$900" not in alerts[0]["text"]
    assert s.outbox(0)[-1]["data"]["priority"] == "high"
    bank.sender_fact(s, {"sender": "33789", "on": False})
    assert bank.senders(s) == []


def test_only_the_phone_confirms_bank_senders():
    from fastapi.testclient import TestClient
    from apollo import main
    from test_server import PHONE
    c = TestClient(main.app)
    r = c.post("/v1/facts", headers=PHONE, json={"facts": [{"kind": "bank_sender", "at": 1, "data": {"sender": "CITI"}}]})
    assert r.status_code == 200 and "citi" in bank.senders(main.store)
    assert c.post("/v1/facts", json={"facts": [{"kind": "bank_sender", "at": 1, "data": {"sender": "x"}}]}).status_code == 401
    assert "bank_sender" not in json.dumps([t["name"] for t in main.TOOLS])


def test_home_snapshots_replace_devices_and_music_logs_plays():
    seed.plant()
    snap = {"link": "Connected", "devices": [
        {"id": "d1", "name": "Desk lamp", "kind": "Light", "room": "Office", "online": True, "on": True, "level": 40, "kelvin": 2700, "controllable": True},
        {"id": "d2", "name": "Hall sensor", "kind": "Sensor", "room": "Hall", "online": True, "temp_c": 19.5, "controllable": False}]}
    seed.ingest("home", 50, snap)
    rows = apps.sql("home", "SELECT id, on_off, level, temp_c, controllable, at FROM devices ORDER BY id")
    assert rows == [{"id": "d1", "on_off": 1, "level": 40, "temp_c": None, "controllable": 1, "at": 50},
                    {"id": "d2", "on_off": None, "level": None, "temp_c": 19.5, "controllable": 0, "at": 50}]
    seed.ingest("home", 60, {"link": "Apollo doesn't have access to your Google Home yet", "devices": []})   # not connected: kept
    assert len(apps.sql("home", "SELECT id FROM devices")) == 2
    seed.ingest("home", 70, {"link": "Connected", "devices": [snap["devices"][0]]})
    assert [r["id"] for r in apps.sql("home", "SELECT id FROM devices")] == ["d1"]
    seed.ingest("music", 80, {"title": "Kesariya", "artist": "Arijit Singh", "uri": "spotify:track:x1", "playing": True})
    assert apps.sql("muse", "SELECT track, artist, uri, source FROM plays WHERE at = 80") == [
        {"track": "Kesariya", "artist": "Arijit Singh", "uri": "spotify:track:x1", "source": "spotify"}]
