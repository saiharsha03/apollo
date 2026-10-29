"""Street map tiles for the pages: TomTom's dark raster map (Map Display API, basic/night), through the server so the key
never reaches the phone. Tiles are kept on disk (config.DATA/tiles, setting tiles_cache_mb, oldest-used out first); a kept
tile is free, a fetched one counts against tomtom_daily_cap. Only z/x/y go to TomTom.
"""
import os
import threading
import urllib.request

from . import config, tomtom

URL = tomtom.HOST + "/map/1/tile/basic/night/{z}/{x}/{y}.png?tileSize=256&view=Unified&key={k}"
MAX_Z = 20
MAX_BYTES = 1024 * 1024
_lock = threading.Lock()
_size = None      # bytes on disk, counted once, then kept up to date


def root():
    return config.DATA / "tiles"


def check(z, x, y):
    if not (0 <= z <= MAX_Z and 0 <= x < 2 ** z and 0 <= y < 2 ** z):
        raise ValueError("no such tile")


def download(url, k):
    """GET one PNG in 10 s; Refused (key cut out) on any failure or anything that isn't a small PNG."""
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            body = r.read(MAX_BYTES + 1)
    except Exception as e:
        raise tomtom.Refused(f"TomTom tile failed ({type(e).__name__}: {tomtom.redact(str(e), k)[:200]})") from None
    if len(body) > MAX_BYTES or not body.startswith(b"\x89PNG"):
        raise tomtom.Refused("TomTom tile failed (not a PNG)")
    return body


def get(store, z, x, y, fetch=None):
    """The tile's PNG bytes: from disk, else TomTom (one call off the day's cap)."""
    check(z, x, y)
    f = root() / str(z) / str(x) / f"{y}.png"
    try:
        body = f.read_bytes()
        os.utime(f)     # last used: the trim keeps it
        return body
    except OSError:
        pass
    k = tomtom.key()
    tomtom.count(store)
    body = (fetch or download)(URL.format(z=z, x=x, y=y, k=k), k)
    f.parent.mkdir(parents=True, exist_ok=True)
    tmp = f.with_suffix(f".{threading.get_ident()}.tmp")
    tmp.write_bytes(body)
    os.replace(tmp, f)
    _added(store, len(body))
    return body


def _added(store, n):
    global _size
    cap = float(store.setting("tiles_cache_mb")) * 1024 * 1024
    with _lock:
        if _size is None:
            _size = sum(p.stat().st_size for p in root().rglob("*.png"))
        else:
            _size += n
        if _size <= cap:
            return
        files = sorted(root().rglob("*.png"), key=lambda p: p.stat().st_mtime)
        for p in files:       # oldest used first, down to 90% of the cap
            if _size <= cap * 0.9:
                break
            try:
                _size -= p.stat().st_size
                p.unlink()
            except OSError:
                pass
