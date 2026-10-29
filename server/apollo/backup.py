"""Mnemosyne's store, for the phone (device token).

Backups arrive sealed by the phone (ABK1 files, docs: app/.../mnemosyne/Sealed.kt); this server never holds the key and
can't open them. Kept: the 14 newest. Streamed to a dot-file and renamed, never overwriting an id.

Zeus's old backups (ZBK1 files sealed under his Zeus recovery phrase, from the retired gateway's /mnt/zeus-backups) are
served read-only from <data>/zeus-backups once copied there (deploy/zeus-backups.sh, not run without Owner's OK), so a
new phone can take Calypso, Calliope and the closet out of them.
"""
import os
import re
import shutil
import time

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse

from . import config

ID = re.compile(r"^\d{8}T\d{6}Z-[0-9a-f]{8}$")
MAX_BYTES = int(os.environ.get("APOLLO_BACKUP_MAX", str(1 << 30)))
KEEP = 14
MIN_FREE = 2 << 30


def folder():
    d = config.DATA / "backups"
    d.mkdir(parents=True, exist_ok=True)
    return d


def listing() -> list[dict]:
    out = [{"id": f.stem, "size": f.stat().st_size, "created": int(f.stat().st_mtime * 1000)} for f in folder().glob("*.abk") if ID.match(f.stem)]
    return sorted(out, key=lambda b: b["id"], reverse=True)


def prune():
    for b in listing()[KEEP:]:
        (folder() / f"{b['id']}.abk").unlink(missing_ok=True)


def router(phone) -> APIRouter:
    r = APIRouter()

    @r.get("/v1/backup")
    def backups(req: Request):
        phone(req)
        return {"backups": listing()}

    @r.put("/v1/backup/{bid}")
    async def put(req: Request, bid: str):
        phone(req)
        if not ID.match(bid):
            raise HTTPException(400, "bad id")
        final = folder() / f"{bid}.abk"
        if final.exists():
            raise HTTPException(409, "exists")
        if shutil.disk_usage(folder()).free < MIN_FREE:
            raise HTTPException(507, "server disk nearly full")
        tmp = folder() / f".{bid}.part"
        size, first = 0, True
        try:
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0), 0o600)
        except FileExistsError:
            raise HTTPException(409, "upload in progress")
        try:
            with os.fdopen(fd, "wb") as f:
                async for chunk in req.stream():
                    if first and chunk:
                        if not chunk.startswith(b"ABK1"):
                            raise HTTPException(400, "not an Apollo backup")
                        first = False
                    size += len(chunk)
                    if size > MAX_BYTES:
                        raise HTTPException(413)
                    f.write(chunk)
                f.flush()
                os.fsync(f.fileno())
            if first:
                raise HTTPException(400, "empty")
            os.replace(tmp, final)
        finally:
            tmp.unlink(missing_ok=True)
        prune()
        return {"ok": True, "id": bid, "size": size, "at": time.time()}

    # Zeus's old backups: before /v1/backup/{bid}, so "zeus" is never read as an id.
    @r.get("/v1/backup/zeus")
    def zeus_backups(req: Request):
        phone(req)
        d = config.DATA / "zeus-backups"
        out = [{"id": f.stem, "size": f.stat().st_size, "created": int(f.stat().st_mtime * 1000)} for f in d.glob("*.zbk") if ID.match(f.stem)] if d.is_dir() else []
        return {"backups": sorted(out, key=lambda b: b["id"], reverse=True)}

    @r.get("/v1/backup/zeus/{bid}")
    def zeus_backup(req: Request, bid: str):
        phone(req)
        f = config.DATA / "zeus-backups" / f"{bid}.zbk"
        if not ID.match(bid) or not f.exists():
            raise HTTPException(404)
        return FileResponse(f, media_type="application/octet-stream")

    @r.get("/v1/backup/{bid}")
    def get(req: Request, bid: str):
        phone(req)
        f = folder() / f"{bid}.abk"
        if not ID.match(bid) or not f.exists():
            raise HTTPException(404)
        return FileResponse(f, media_type="application/octet-stream")

    return r
