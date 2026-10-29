"""Prometheus, the server side: the newest signed Apollo for the phone.

deploy/apollo-release.sh hands an unsigned APK to Owner's signing service (release-signer: he approves with his
fingerprint in the authenticator; the signer keeps the key, nothing here ever signs) and leaves the signed APK and update.json in
/var/lib/apollo-updates (config.UPDATES). GET /v1/update offers it ({} while there is none); the phone checks the sha256, the package, a newer
version and the same signer before Android asks him to install.
"""
import json

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse

from . import config

FIELDS = ("version_code", "version_name", "sha256", "size", "notes")


def release():
    """(update.json, its APK) for the release on disk, or None."""
    d = config.UPDATES
    try:
        j = json.loads((d / "update.json").read_text())
        apk = d / f"apollo-{int(j['version_code'])}.apk"
    except (OSError, ValueError, KeyError, TypeError):
        return None
    return (j, apk) if apk.is_file() and apk.stat().st_size == j.get("size") else None


def router(phone):
    r = APIRouter()

    @r.get("/v1/update")
    def update(req: Request):
        phone(req)
        rel = release()
        return {k: rel[0].get(k) for k in FIELDS} if rel else {}

    @r.get("/v1/update/apk")
    def update_apk(req: Request, v: int = 0):
        phone(req)
        rel = release()
        if not rel or (v and v != int(rel[0]["version_code"])):
            raise HTTPException(404, "no such signed update")
        return FileResponse(rel[1], media_type="application/vnd.android.package-archive", filename=f"apollo-{v or rel[0]['version_code']}.apk")

    return r
