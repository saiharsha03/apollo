"""Street map tiles: TomTom's dark tile through the server, kept on disk, the key never in a reply."""
import os

import pytest

from test_server import PHONE, ROOT, tools  # noqa: F401  (env and fixtures)

from apollo import config, tiles, tomtom

PNG = b"\x89PNG\r\n\x1a\n" + b"x" * 100


@pytest.fixture
def disk(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "DATA", tmp_path)
    monkeypatch.setattr(tiles, "_size", None)
    (tmp_path / "tomtom.key").write_text("abcDEF1234567890")
    monkeypatch.setattr(config, "TOMTOM_KEY", tmp_path / "tomtom.key")
    return tmp_path


def test_a_tile_is_fetched_once_then_kept(tools, disk):
    urls = []
    fake = lambda url, k: urls.append(url) or PNG  # noqa: E731
    assert tiles.get(tools.store, 12, 1130, 1513, fetch=fake) == PNG
    assert tiles.get(tools.store, 12, 1130, 1513, fetch=fake) == PNG
    assert len(urls) == 1 and "/map/1/tile/basic/night/12/1130/1513.png" in urls[0]
    assert tools.store.setting("tomtom_calls")["n"] == 1     # the kept one was free


def test_bad_tiles_and_the_cap(tools, disk):
    for z, x, y in ((21, 0, 0), (2, 4, 0), (2, 0, -1), (-1, 0, 0)):
        with pytest.raises(ValueError):
            tiles.get(tools.store, z, x, y, fetch=lambda u, k: PNG)
    tools.store.set_setting("tomtom_daily_cap", 1)
    tiles.get(tools.store, 3, 1, 1, fetch=lambda u, k: PNG)
    with pytest.raises(tomtom.Refused, match="daily"):
        tiles.get(tools.store, 3, 1, 2, fetch=lambda u, k: PNG)
    assert tiles.get(tools.store, 3, 1, 1, fetch=None) == PNG   # kept: no call, no cap


def test_the_oldest_used_tiles_go_past_the_size_cap(tools, disk):
    tools.store.set_setting("tiles_cache_mb", 250 / 1024 / 1024)     # room for two
    for y in range(2):
        tiles.get(tools.store, 4, 0, y, fetch=lambda u, k: PNG)
    os.utime(disk / "tiles" / "4" / "0" / "0.png", (1, 1))
    os.utime(disk / "tiles" / "4" / "0" / "1.png", (2, 2))
    tiles.get(tools.store, 4, 0, 1, fetch=None)      # used again: newest
    tiles.get(tools.store, 4, 0, 2, fetch=lambda u, k: PNG)
    assert sorted(p.name for p in (disk / "tiles" / "4" / "0").iterdir()) == ["1.png", "2.png"]


def test_a_failed_fetch_never_shows_the_key(monkeypatch):
    def boom(url, timeout):
        raise OSError("refused " + url)
    monkeypatch.setattr("urllib.request.urlopen", boom)
    with pytest.raises(tomtom.Refused) as e:
        tiles.download("https://api.tomtom.com/x?key=abcDEF1234567890", "abcDEF1234567890")
    assert "abcDEF" not in str(e.value) and "[key]" in str(e.value)


def test_the_route_needs_the_phone_and_answers_png(disk, monkeypatch):
    from fastapi.testclient import TestClient
    from apollo import main
    monkeypatch.setattr(tiles, "download", lambda url, k: PNG)
    c = TestClient(main.app)
    assert c.get("/v1/tiles/3/1/1.png").status_code == 401
    r = c.get("/v1/tiles/3/1/1.png", headers=PHONE)
    assert r.status_code == 200 and r.content == PNG and r.headers["content-type"] == "image/png"
    assert c.get("/v1/tiles/3/9/1.png", headers=PHONE).status_code == 404
    config.TOMTOM_KEY.unlink()
    assert c.get("/v1/tiles/3/1/2.png", headers=PHONE).status_code == 503
