"""FCM HTTP v1 wake-ups. The data is exactly {"t": "sync"}: Google never sees what changed; the phone fetches /v1/sync.
Bursts within a few seconds collapse into one wake."""
import base64
import json
import threading
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

from . import config

SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_lock = threading.Lock()
_access = [None, 0.0]
_last = [0.0]
_last_activity = [0.0]
ACTIVITY_GAP = 30   # an activity wakes the phone at most this often, and not at all when it fetched this recently


def payload(token: str, kind: str = "") -> dict:
    if kind == "activity":   # only the pixel characters: normal priority, its own collapse key, short-lived
        return {"message": {"token": token, "data": {"t": "sync"}, "android": {"priority": "normal", "collapse_key": "activity", "ttl": "300s"}}}
    return {"message": {"token": token, "data": {"t": "sync"}, "android": {"priority": "high", "collapse_key": "apollo-sync", "ttl": "3600s"}}}


def should_wake(store, kind: str, now: float) -> bool:
    """Bursts within a few seconds collapse into one wake. An activity skips the wake while the phone is fetching on its
    own (Apollo on screen polls /v1/activity), and otherwise goes at most once per ACTIVITY_GAP."""
    if kind == "activity":
        if now - store.phone_seen < ACTIVITY_GAP or now - _last_activity[0] < ACTIVITY_GAP:
            return False
        _last_activity[0] = now
        return True
    if now - _last[0] < 3:
        return False
    _last[0] = now
    return True


def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def _bearer(sa: dict) -> str:
    if _access[0] and time.time() < _access[1] - 60:
        return _access[0]
    now = int(time.time())
    head = _b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = _b64(json.dumps({"iss": sa["client_email"], "scope": SCOPE, "aud": sa["token_uri"], "iat": now, "exp": now + 3600}).encode())
    key = serialization.load_pem_private_key(sa["private_key"].encode(), None)
    sig = _b64(key.sign(f"{head}.{claims}".encode(), padding.PKCS1v15(), hashes.SHA256()))
    body = f"grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion={head}.{claims}.{sig}".encode()
    with urllib.request.urlopen(urllib.request.Request(sa["token_uri"], body, {"Content-Type": "application/x-www-form-urlencoded"}), timeout=15) as r:
        d = json.loads(r.read())
    _access[:] = [d["access_token"], time.time() + int(d.get("expires_in", 3600))]
    return d["access_token"]


def wake(store, kind: str = ""):
    """Blocking; call from a thread. See should_wake for when it skips."""
    if not config.FCM_ACCOUNT.exists():
        return
    with _lock:
        if not should_wake(store, kind, time.time()):
            return
        try:    # a bad account file or a failed token exchange: a status, never a dead thread
            sa = json.loads(config.FCM_ACCOUNT.read_text())
            url = f"https://fcm.googleapis.com/v1/projects/{sa['project_id']}/messages:send"
            bearer = _bearer(sa)
        except Exception as e:
            store.set_status("fcm", False, f"token: {type(e).__name__}")
            return
        for row in store.q("SELECT token FROM fcm"):
            req = urllib.request.Request(url, json.dumps(payload(row["token"], kind)).encode(), {"Authorization": f"Bearer {bearer}", "Content-Type": "application/json"})
            try:
                urllib.request.urlopen(req, timeout=15).close()
                store.set_status("fcm", True, "wake sent")
            except urllib.error.HTTPError as e:
                store.set_status("fcm", False, f"HTTP {e.code}")
                # Only a token FCM says is gone; a 404 for a wrong project id keeps it.
                try:
                    body = e.read() or b""
                except OSError:     # the error body itself can break off: never the wake thread with it
                    body = b""
                if e.code == 404 and b"UNREGISTERED" in body:
                    store.x("DELETE FROM fcm WHERE token = ?", (row["token"],))
            except OSError as e:
                store.set_status("fcm", False, type(e).__name__)
