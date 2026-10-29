import json
import os

from test_server import tools  # noqa: F401  (the tools fixture)

from apollo import apps, securitylog, seed


def test_drops_land_in_the_security_app_and_leave_the_spool(tools, tmp_path, monkeypatch):
    seed.plant()
    monkeypatch.setattr(securitylog, "_owner", lambda uid: "root")
    (tmp_path / "1.json").write_text(json.dumps({"source": "security", "text": "SSH login: root @ 1.2.3.4", "ts": 1793194060.8}))
    (tmp_path / "2.json").write_text("not json")
    (tmp_path / "3.json").write_text(json.dumps({"source": "media", "text": "  "}))
    assert securitylog.intake(tmp_path) == 1
    assert not list(tmp_path.glob("*.json"))
    r = apps.sql("security", "SELECT * FROM events")
    assert [(e["at"], e["source"], e["text"], e["origin"]) for e in r] == [(1793194060800, "security", "SSH login: root @ 1.2.3.4", "root")]


def test_a_drop_from_anyone_else_is_dropped(tools, tmp_path, monkeypatch):
    seed.plant()
    monkeypatch.setattr(securitylog, "_owner", lambda uid: None)
    before = apps.sql("security", "SELECT count(*) AS n FROM events")[0]["n"]
    (tmp_path / "1.json").write_text(json.dumps({"source": "security", "text": "SSH login: fake"}))
    assert securitylog.intake(tmp_path) == 0 and not os.listdir(tmp_path)
    assert apps.sql("security", "SELECT count(*) AS n FROM events")[0]["n"] == before


def test_bad_time_never_blocks_and_agents_cant_claim_security(tools, tmp_path, monkeypatch):
    seed.plant()
    monkeypatch.setattr(securitylog, "_owner", lambda uid: "agents")
    (tmp_path / "1.json").write_text('{"source": "security", "text": "SSH login: fake", "ts": 1e400}')
    (tmp_path / "2.json").write_text(json.dumps({"source": "media", "text": "Downloaded x"}))
    assert securitylog.intake(tmp_path) == 2 and not os.listdir(tmp_path)
    r = apps.sql("security", "SELECT source, origin FROM events ORDER BY id DESC LIMIT 2")
    assert sorted((e["source"], e["origin"]) for e in r) == [("agents", "agents"), ("media", "agents")]
