"""Spotify's Web API for the agents (Zeus's SpotifyWeb/SpotifyManage, read side): search, his playlists and their tracks,
top and recently played, now playing, album art. His sign-in comes over from the phone once (Zeus's sealed tokens,
handed over with his fingerprint like the TomTom key); after that the server owns it and refreshes it itself with the
public client id (PKCE, no secret). Only accounts.spotify.com and api.spotify.com are called. Tokens never reach a log,
an error or a reply.
"""
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from . import config
from .tomtom import Refused

CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"    # public (dev.apollo.muse.SpotifyConfig)
TOKEN_URL = "https://accounts.spotify.com/api/token"
API = "https://api.spotify.com/v1"
KINDS = ("search", "playlists", "playlist_tracks", "top", "recent", "now", "art", "devices", "scopes")
# What phone_do spotify's Web API side needs (devices, moving playback) and the playlist cards; App Remote needs none.
NEEDS = ("user-read-playback-state", "user-modify-playback-state", "playlist-modify-private", "playlist-modify-public")
RANGES = ("short_term", "medium_term", "long_term")
_lock = threading.Lock()


def path():
    return config.DATA / "spotify.json"


def save(t: dict):
    p = path()
    tmp = p.with_name(p.name + ".tmp")
    tmp.write_text(json.dumps(t))
    os.chmod(tmp, 0o600)
    os.replace(tmp, p)


def handover(b: dict) -> dict:
    """The phone's tokens ({access, refresh, expiresAt, scope}, Zeus's shape). Raises ValueError on a wrong shape."""
    refresh, access = str(b.get("refresh", "")).strip(), str(b.get("access", "")).strip()
    if not 20 <= len(refresh) <= 1000 or len(access) > 2000:
        raise ValueError("not a Spotify sign-in")
    t = {"access": access, "refresh": refresh, "expires_at": int(b.get("expiresAt") or b.get("expires_at") or 0), "scope": str(b.get("scope", ""))[:1000]}
    save(t)
    return {"scope": t["scope"]}


def _scrub(text, t):
    for s in (t.get("access"), t.get("refresh")):
        if s:
            text = text.replace(s, "[token]")
    return text


def _http(req, t):
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            body = r.read()
            return r.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        return e.code, {}
    except Exception as e:
        raise Refused(f"Spotify failed ({type(e).__name__}: {_scrub(str(e), t)[:200]})") from None


def token(now=None) -> str:
    """A live access token, refreshed a minute before it expires. A refresh token Spotify refused signs him out."""
    with _lock:
        try:
            t = json.loads(path().read_text())
        except (OSError, ValueError):
            raise Refused("Spotify isn't connected on the server yet (Pair screen > Send key)") from None
        now = now or time.time() * 1000
        if t.get("access") and now < t.get("expires_at", 0) - 60_000:
            return t["access"]
        form = urllib.parse.urlencode({"grant_type": "refresh_token", "refresh_token": t["refresh"], "client_id": CLIENT_ID}).encode()
        code, j = _http(urllib.request.Request(TOKEN_URL, data=form, headers={"Content-Type": "application/x-www-form-urlencoded"}), t)
        if code == 400:
            path().unlink(missing_ok=True)
            raise Refused("Spotify refused his sign-in (revoked or replaced); send it again from the phone after signing in")
        if code != 200 or not j.get("access_token"):
            raise Refused(f"Spotify sign-in refresh failed ({code})")
        # Spotify may rotate the refresh token; when it doesn't the old one stays valid. A refresh keeps the scopes granted.
        t.update(access=j["access_token"], refresh=j.get("refresh_token") or t["refresh"], expires_at=int(now + 1000 * int(j.get("expires_in", 3600))))
        save(t)
        return t["access"]


def api(p: str, params=None):
    tok = token()
    url = API + p + ("?" + urllib.parse.urlencode(params) if params else "")
    code, j = _http(urllib.request.Request(url, headers={"Authorization": "Bearer " + tok}), {"access": tok})
    if code == 204:
        return None
    if code == 401:
        raise Refused("Spotify said the sign-in isn't valid; send it again from the phone")
    if code == 403:
        raise Refused("Spotify refused (a scope he didn't grant)")
    if code == 404:
        raise Refused("Spotify found nothing there")
    if code == 429:
        raise Refused("Spotify says too many calls; try later")
    if code != 200:
        raise Refused(f"Spotify answered {code}")
    return j


def _art(images):
    """The cover URL (i.scdn.co), the medium size when there are several."""
    images = images or []
    return (images[1] if len(images) > 1 else images[0])["url"] if images else None


def row(it):
    """One item (track, artist, album, playlist) as names, its URI and cover; never more of Spotify's payload."""
    if not it:
        return None
    kind = it.get("type")
    out = {"name": it.get("name"), "uri": it.get("uri"), "type": kind}
    if kind == "track":
        out["by"] = ", ".join(a.get("name", "") for a in it.get("artists", []))
        out["album"] = (it.get("album") or {}).get("name")
        out["art"] = _art((it.get("album") or {}).get("images"))
        out["minutes"] = round(it.get("duration_ms", 0) / 60000, 1)
    elif kind == "playlist":
        out["owner"] = (it.get("owner") or {}).get("display_name")
        out["tracks"] = ((it.get("tracks") or it.get("items") or {}).get("total"))
        out["art"] = _art(it.get("images"))
    else:
        out["art"] = _art(it.get("images"))
        if kind == "album":
            out["by"] = ", ".join(a.get("name", "") for a in it.get("artists", []))
    return out


def _limit(a, default, top):
    return max(1, min(int(a.get("limit", default)), top))


def _id(ref, kind):
    ref = str(ref).strip()
    if ref.startswith(f"spotify:{kind}:"):
        return ref.split(":")[-1]
    if "open.spotify.com/" in ref:
        return ref.split(f"/{kind}/")[-1].split("?")[0]
    return ref


def run(a: dict):
    kind = a.get("kind")
    if kind == "search":
        q, t = str(a.get("query", "")).strip(), a.get("type") or "track"
        if not q:
            raise ValueError("query is empty")
        if t not in ("track", "artist", "album", "playlist"):
            raise ValueError("type is track, artist, album or playlist")
        j = api("/search", {"q": q[:200], "type": t, "limit": _limit(a, 5, 10)})   # Spotify's page cap since Feb 2026
        return [row(x) for x in (j.get(t + "s") or {}).get("items", []) if x]
    if kind == "playlists":
        j = api("/me/playlists", {"limit": _limit(a, 50, 50)})
        return [row(x) for x in j.get("items", []) if x]
    if kind == "playlist_tracks":
        pid = _id(a.get("playlist", ""), "playlist")
        if not pid:
            raise ValueError("playlist: its URI or id (from playlists)")
        j = api(f"/playlists/{urllib.parse.quote(pid, safe='')}/items", {"limit": _limit(a, 50, 50), "offset": max(0, int(a.get("offset", 0)))})
        return [row(x.get("item") or x.get("track")) for x in j.get("items", []) if x.get("item") or x.get("track")]
    if kind == "top":
        t = (a.get("type") or "tracks").rstrip("s") + "s"
        r = a.get("time_range") or "medium_term"
        if t not in ("tracks", "artists") or r not in RANGES:
            raise ValueError("type tracks or artists; time_range short_term, medium_term or long_term")
        j = api(f"/me/top/{t}", {"time_range": r, "limit": _limit(a, 20, 50)})
        return [row(x) for x in j.get("items", [])]
    if kind == "recent":
        j = api("/me/player/recently-played", {"limit": _limit(a, 20, 50)})
        return [dict(row(x.get("track")), played_at=x.get("played_at")) for x in j.get("items", []) if x.get("track")]
    if kind == "now":
        j = api("/me/player/currently-playing")
        if not j or not j.get("item"):
            return "Nothing playing on his Spotify."
        return dict(row(j["item"]), playing=bool(j.get("is_playing")), at_minutes=round(j.get("progress_ms", 0) / 60000, 1))
    if kind == "art":
        uri = str(a.get("uri", "")).strip()
        for k in ("track", "album", "artist", "playlist"):
            if uri.startswith(f"spotify:{k}:"):
                j = api(f"/{k}s/{urllib.parse.quote(uri.split(':')[-1], safe='')}")
                return {"uri": uri, "art": row(j)["art"] if k != "track" else _art((j.get("album") or {}).get("images"))}
        raise ValueError("uri: a spotify:track/album/artist/playlist URI")
    if kind == "devices":
        j = api("/me/player/devices") or {}
        return [{"id": d.get("id"), "name": d.get("name"), "type": d.get("type"), "active": bool(d.get("is_active")),
                 "volume": d.get("volume_percent")} for d in j.get("devices", [])]
    if kind == "scopes":
        token()     # signed in at all
        granted = set(json.loads(path().read_text()).get("scope", "").split())
        return {"granted": sorted(granted), "missing": [x for x in NEEDS if x not in granted]}
    raise ValueError("kind is " + ", ".join(KINDS))


def router(phone):
    """The phone's playlist changes (phone_do spotify_playlist, after his tap) call api.spotify.com themselves with a
    short-lived access token from here; the refresh token stays on the server, its one owner."""
    from fastapi import APIRouter, HTTPException, Request
    r = APIRouter()

    @r.get("/v1/spotify/access")
    def access(req: Request):
        phone(req)
        try:
            tok = token()
        except Refused as e:
            raise HTTPException(503, str(e))
        return {"access": tok, "expires_at": json.loads(path().read_text()).get("expires_at", 0)}

    # Muse's page reads his Spotify through the phone (apollo.spotify): search, his playlists, a playlist's songs. Read
    # only; a refusal comes back as {ok: false, detail} so the page can say why.
    def read(kind, a):
        try:
            return {"ok": True, "items": run(dict(a, kind=kind))}
        except (Refused, ValueError) as e:
            return {"ok": False, "detail": str(e)}

    @r.get("/v1/spotify/search")
    def search(req: Request, q: str = "", type: str = "track", limit: int = 10):
        phone(req)
        return read("search", {"query": q, "type": type, "limit": limit})

    @r.get("/v1/spotify/playlists")
    def playlists(req: Request):
        phone(req)
        return read("playlists", {})

    @r.get("/v1/spotify/playlist_tracks")
    def playlist_tracks(req: Request, playlist: str = "", offset: int = 0):
        phone(req)
        return read("playlist_tracks", {"playlist": playlist, "offset": offset})

    return r
