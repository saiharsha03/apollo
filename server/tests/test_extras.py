"""Extras: Sarvam voice, the Atlas bridge, signed updates, theme files."""
import base64
import io
import json
import urllib.error
import wave

from test_server import PHONE, ROOT, tools  # noqa: F401  (env and fixtures)

from apollo import config


class FakeSarvam:
    """urlopen stand-in for api.sarvam.ai: records requests, answers `body` or raises `error`."""
    def __init__(self, body=None, error=None):
        self.reqs, self.body, self.error = [], body, error

    def __call__(self, req, timeout):
        self.reqs.append(req)
        if self.error:
            raise self.error
        return io.BytesIO(json.dumps(self.body).encode())


def client():
    from fastapi.testclient import TestClient
    from apollo import main
    return TestClient(main.app), main


def wav(seconds):
    b = io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"\0\0" * 16000 * seconds)
    return b.getvalue()


def test_tts_needs_the_key_and_sends_athenas_words(monkeypatch):
    c, main = client()
    config.SARVAM_KEY.unlink(missing_ok=True)
    assert c.post("/v1/voice/tts", json={"text": "hi"}).status_code == 401
    assert c.post("/v1/voice/tts", headers=PHONE, json={"text": "hi"}).status_code == 503
    config.SARVAM_KEY.write_text("SARVAM_API_KEY=sk_test_123456\n")
    fake = FakeSarvam({"audios": [base64.b64encode(b"RIFFwav").decode()]})
    monkeypatch.setattr("urllib.request.urlopen", fake)
    r = c.post("/v1/voice/tts", headers=PHONE, json={"text": "Leave by 8:10", "speaker": "nobody"})
    assert r.status_code == 200 and r.content == b"RIFFwav" and r.headers["content-type"] == "audio/wav"
    req = fake.reqs[0]
    assert req.full_url == "https://api.sarvam.ai/text-to-speech" and req.get_header("Api-subscription-key") == "sk_test_123456"
    assert json.loads(req.data)["speaker"] == "ritu" and c.post("/v1/voice/tts", headers=PHONE, json={"text": " "}).status_code == 400


def test_stt_splits_long_wavs_and_joins_the_words(monkeypatch):
    c, main = client()
    config.SARVAM_KEY.write_text("sk_test_123456")
    fake = FakeSarvam({"transcript": "milk"})
    monkeypatch.setattr("urllib.request.urlopen", fake)
    r = c.post("/v1/voice/stt", headers={**PHONE, "Content-Type": "audio/wav"}, content=wav(60))
    assert r.json() == {"text": "milk milk milk"} and len(fake.reqs) == 3
    assert b'name="model"' in fake.reqs[0].data and fake.reqs[0].full_url.endswith("/speech-to-text")


def test_sarvam_key_never_leaks_and_the_cap_holds(monkeypatch):
    c, main = client()
    config.SARVAM_KEY.write_text("sk_test_123456")
    monkeypatch.setattr("urllib.request.urlopen", FakeSarvam(error=urllib.error.URLError("refused sk_test_123456")))
    r = c.post("/v1/voice/tts", headers=PHONE, json={"text": "hi"})
    assert r.status_code == 502 and "sk_test_123456" not in r.text
    main.store.set_setting("sarvam_daily_cap", 0)
    r = c.post("/v1/voice/tts", headers=PHONE, json={"text": "hi"})
    assert r.status_code == 429
    main.store.set_setting("sarvam_daily_cap", 300)


def test_sarvam_cap_is_a_setting(tools):
    tools.run("athena", "phone_setting", {"name": "sarvam_daily_cap", "value": 50, "why": "w"})
    assert tools.store.setting("sarvam_daily_cap") == 50 and not tools.store.outbox(0)


def bridge():
    import importlib.util
    from pathlib import Path
    spec = importlib.util.spec_from_file_location("atlas_bridge", Path(__file__).resolve().parents[1] / "deploy" / "atlas-apollo-bridge.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    m.log = lambda _m: None
    return m


class FakeSock:
    def __init__(self, b):
        self.sent, self.b = [], b

    def sendall(self, data):
        f = json.loads(data)
        self.sent.append(f)
        if f["op"] == "tool":     # the server answers at once
            self.b.handle({"op": "result", "id": f["id"], "text": "Posted"})


def test_update_offers_the_signed_release_only_when_its_apk_is_there():
    c, main = client()
    d = config.UPDATES
    d.mkdir(exist_ok=True)
    assert c.get("/v1/update", headers=PHONE).json() == {}
    rel = {"version_code": 31, "version_name": "1.3", "sha256": "a" * 64, "size": 7, "notes": "voice"}
    (d / "update.json").write_text(json.dumps(rel))
    assert c.get("/v1/update", headers=PHONE).json() == {} and c.get("/v1/update/apk", headers=PHONE).status_code == 404
    (d / "apollo-31.apk").write_bytes(b"APKdata")
    assert c.get("/v1/update").status_code == 401
    assert c.get("/v1/update", headers=PHONE).json() == rel
    assert c.get("/v1/update/apk?v=31", headers=PHONE).content == b"APKdata"
    assert c.get("/v1/update/apk?v=30", headers=PHONE).status_code == 404
    for f in d.iterdir():
        f.unlink()


def test_phone_gets_a_spotify_access_token_never_the_refresh():
    import time
    from apollo import spotify
    c, main = client()
    spotify.path().unlink(missing_ok=True)
    assert c.get("/v1/spotify/access", headers=PHONE).status_code == 503
    spotify.save({"access": "acc-123", "refresh": "ref-" + "x" * 30, "expires_at": int(time.time() * 1000) + 3_600_000, "scope": ""})
    assert c.get("/v1/spotify/access").status_code == 401
    j = c.get("/v1/spotify/access", headers=PHONE).json()
    assert j["access"] == "acc-123" and "refresh" not in json.dumps(j)
    spotify.path().unlink()


def test_spotify_lists_devices_and_says_which_scopes_are_missing(monkeypatch):
    import time
    from apollo import spotify
    spotify.save({"access": "acc-123", "refresh": "ref-" + "x" * 30, "expires_at": int(time.time() * 1000) + 3_600_000,
                  "scope": "user-read-playback-state playlist-modify-private"})
    monkeypatch.setattr(spotify, "api", lambda p, params=None: {"devices": [
        {"id": "d1", "name": "Kitchen", "type": "Speaker", "is_active": True, "volume_percent": 40, "is_restricted": False}]})
    assert spotify.run({"kind": "devices"}) == [{"id": "d1", "name": "Kitchen", "type": "Speaker", "active": True, "volume": 40}]
    assert spotify.run({"kind": "scopes"})["missing"] == ["user-modify-playback-state", "playlist-modify-public"]
    spotify.path().unlink()


def test_muse_page_reads_search_and_playlists_through_the_phone(monkeypatch):
    import time
    from apollo import spotify
    c, main = client()
    spotify.path().unlink(missing_ok=True)
    assert c.get("/v1/spotify/playlists").status_code == 401
    assert "isn't connected" in c.get("/v1/spotify/playlists", headers=PHONE).json()["detail"]
    spotify.save({"access": "acc-123", "refresh": "ref-" + "x" * 30, "expires_at": int(time.time() * 1000) + 3_600_000, "scope": ""})
    seen = []
    track = {"type": "track", "name": "Song", "uri": "spotify:track:1", "artists": [{"name": "A"}], "album": {"name": "Al"}}
    monkeypatch.setattr(spotify, "api", lambda p, params=None: seen.append((p, params)) or {"tracks": {"items": [track]}, "items": [{"item": track}]})
    j = c.get("/v1/spotify/search", params={"q": "song", "type": "track", "limit": 3}, headers=PHONE).json()
    assert j["ok"] and j["items"][0]["uri"] == "spotify:track:1" and seen[-1] == ("/search", {"q": "song", "type": "track", "limit": 3})
    assert c.get("/v1/spotify/playlist_tracks", params={"playlist": "spotify:playlist:abc"}, headers=PHONE).json()["items"][0]["name"] == "Song"
    assert seen[-1][0] == "/playlists/abc/items"
    assert c.get("/v1/spotify/search", params={"q": ""}, headers=PHONE).json() == {"ok": False, "detail": "query is empty"}
    assert "acc-123" not in json.dumps(c.get("/v1/spotify/playlists", headers=PHONE).json())
    spotify.path().unlink()


def test_theme_setting_names_a_theme_file(tools):
    from apollo import apps, seed
    seed.plant()
    assert (apps.root() / "themes" / "default.json").is_file()
    assert "theme = default" in tools.run("athena", "phone_setting", {"name": "theme", "value": "default", "why": "w"})
    assert "Not done" in tools.run("athena", "phone_setting", {"name": "theme", "value": "night", "why": "w"})
    tools.run("athena", "app_write", {"app": "themes", "path": "night.json", "text": "{}", "why": "w"})
    assert "theme = night" in tools.run("athena", "phone_setting", {"name": "theme", "value": "night", "why": "w"})
    (apps.root() / "themes" / "night.json").unlink()


def test_lemnos_new_folder_follows_the_write_rules():
    c, main = client()
    (config.WORKSPACE / "nf").mkdir(parents=True, exist_ok=True)
    post = lambda **b: c.post("/v1/lemnos/folder", headers=PHONE, json=b)
    assert c.post("/v1/lemnos/folder", json={"root": "workspace", "path": "nf/a"}).status_code == 401
    assert post(root="workspace", path="nf/plans").status_code == 200 and (config.WORKSPACE / "nf" / "plans").is_dir()
    assert post(root="workspace", path="nf/plans").status_code == 409
    for root, path in (("workspace", "../x"), ("workspace", "nf/.git"), ("workspace", "nf/data.db"), ("data", "x"), ("workspace", "nope/x")):
        assert post(root=root, path=path).status_code in (400, 404), (root, path)
