"""Athena's mini apps: one folder per app under config.APPS (the Calliope pattern, docs/PLAN.md).

  app.json      title, icon, about, whether it is a section, its pages, and `phone_tables` (rows only the phone's facts write)
  README.md     what it does (hers)
  CHANGELOG.md  a line for every change, with the why
  data.db       its own SQLite tables
  pages/ notes/ widgets/ and any other text file

Agents write text files only (binaries arrive from the phone or Owner), never outside their app, and never a phone table.
A killed app moves to _archive/ and can be restored; nothing here deletes an app for good.
"""
import hashlib
import json
import os
import re
import shutil
import sqlite3
import threading
import time
from pathlib import Path

from . import config

TEXT = {"md", "yml", "yaml", "json", "html", "css", "js", "txt", "csv", "svg"}
NAME = re.compile(r"^[a-z][a-z0-9-]{1,39}$")
FRAMES = re.compile(r"<\s*(iframe|frame|frameset|object|embed|portal|fencedframe)\b", re.I)


class AppError(Exception):
    pass


def root() -> Path:
    config.APPS.mkdir(parents=True, exist_ok=True)
    return config.APPS


def _dir(app: str) -> Path:
    if not NAME.match(app or ""):
        raise AppError(f"'{app}' isn't an app name (lower-case letters, digits and dashes)")
    d = root() / app
    if not (d / "app.json").is_file():
        raise AppError(f"there is no app called {app}")
    return d


def _path(app: str, rel: str) -> Path:
    d = _dir(app)
    p = (d / rel).resolve()
    if d.resolve() not in p.parents:
        raise AppError("that path is outside the app")
    return p


def manifest(app: str) -> dict:
    return json.loads((_dir(app) / "app.json").read_text(encoding="utf-8"))


# app.json and .version are read, changed and written back from several threads (tools, facts, imports): one lock.
LOCK = threading.RLock()


def _write(p: Path, text: str):
    """Whole or not at all: readers that don't take LOCK (/v1/sync's version, every manifest) never see half a file."""
    tmp = p.with_name(f".{p.name}.{threading.get_ident()}.tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, p)


def _bump():
    with LOCK:
        v = root() / ".version"
        n = int(v.read_text()) + 1 if v.exists() else 1
        _write(v, str(n))
        return n


def add_phone_tables(app: str, names) -> list:
    """Lists tables as the phone's (only the facts ingester writes them); the ones that were new."""
    with LOCK:
        m = manifest(app)
        new = [n for n in names if n not in m.get("phone_tables", [])]
        if new:
            m["phone_tables"] = list(m.get("phone_tables", [])) + new
            _write(_dir(app) / "app.json", json.dumps(m, indent=2))
        return new


def version() -> int:
    v = root() / ".version"
    return int(v.read_text()) if v.exists() else 0


def _changelog(d: Path, who: str, what: str, why: str):
    line = f"- {time.strftime('%Y-%m-%d %H:%M')} {who}: {what}" + (f" (why: {why})" if why else "") + "\n"
    with open(d / "CHANGELOG.md", "a", encoding="utf-8") as f:
        f.write(line)
    with open(root() / "CHANGELOG.md", "a", encoding="utf-8") as f:
        f.write(f"- {time.strftime('%Y-%m-%d %H:%M')} {who} in {d.name}: {what}" + (f" (why: {why})" if why else "") + "\n")


def _index():
    rows = []
    for d in sorted(root().iterdir()):
        if d.is_dir() and (d / "app.json").is_file():
            m = json.loads((d / "app.json").read_text(encoding="utf-8"))
            rows.append(f"- **{m.get('title', d.name)}** (`{d.name}`): {m.get('about', '')}")
    (root() / "INDEX.md").write_text("# Apps\n\n" + "\n".join(rows) + "\n", encoding="utf-8")


def list_apps() -> list:
    out = []
    for d in sorted(root().iterdir()):
        if d.is_dir() and (d / "app.json").is_file():
            out.append(json.loads((d / "app.json").read_text(encoding="utf-8")) | {"name": d.name})
    return out


def create(app: str, title: str, icon: str, about: str, section: bool, why: str, who: str, phone_tables=()) -> str:
    if not NAME.match(app or ""):
        raise AppError("name: lower-case letters, digits and dashes, starting with a letter")
    d = root() / app
    if d.exists():
        raise AppError(f"{app} already exists; change it with app_write")
    if (root() / "_archive" / app).exists():
        raise AppError(f"{app} is archived; restore it instead of making a new one")
    d.mkdir(parents=True)
    now = time.time()
    m = {"name": app, "title": title[:40] or app, "icon": icon[:20], "about": about[:300], "section": bool(section),
         "pages": [], "widgets": [], "phone_tables": list(phone_tables), "created": now, "changed": now}
    with LOCK:
        _write(d / "app.json", json.dumps(m, indent=2))
    _changelog(d, who, "made the app", why)
    _index()
    _bump()
    return f"Made {app}. Write its pages (pages/<page>.html or .md), notes (notes/*.md), tables (app_sql) and widgets (widgets/<w>.html)."


def read(app: str, rel: str) -> str:
    p = _path(app, rel)
    if not p.is_file():
        raise AppError(f"{rel} isn't in {app}")
    if p.suffix.lstrip(".").lower() not in TEXT:
        return f"({rel} is a {p.stat().st_size}-byte binary file)"
    return p.read_text(encoding="utf-8")


def files(app: str) -> list:
    d = _dir(app)
    return sorted(str(p.relative_to(d)).replace("\\", "/") for p in d.rglob("*") if p.is_file())


def _look_beside(p: Path, text: str):
    """A page that links apollo.css gets Apollo's look and fonts beside it when its folder has none: without them its
    text is black on the dark ground and the page looks empty (Drives, 2026-10-27)."""
    if p.suffix.lower() != ".html" or "apollo.css" not in text or (p.parent / "apollo.css").exists():
        return
    from .seed import SEED
    src = SEED / "money" / "pages"
    shutil.copy2(src / "apollo.css", p.parent / "apollo.css")
    if not (p.parent / "fonts").exists():
        shutil.copytree(src / "fonts", p.parent / "fonts")


def write(app: str, rel: str, text: str, why: str, who: str) -> str:
    return write_keep(app, rel, text, why, who)[0]


def write_keep(app: str, rel: str, text: str, why: str, who: str) -> tuple:
    """write, and where the file's old text was kept (_undo/..., for changes.py's Undo), or None for a new file."""
    p = _path(app, rel)
    name = p.name
    if name in ("data.db", "app.json"):
        raise AppError(f"{name} changes through {'app_sql' if name == 'data.db' else 'app_create / app_set'}")
    if name == "CHANGELOG.md":
        raise AppError("CHANGELOG.md is the record of changes: Apollo writes it")
    if p.suffix.lstrip(".").lower() not in TEXT:
        raise AppError("text files only: " + ", ".join(sorted(TEXT)))
    if p.suffix.lower() in (".html", ".svg", ".js") and FRAMES.search(text):
        raise AppError("pages can't embed other pages (iframe, object, embed)")
    p.parent.mkdir(parents=True, exist_ok=True)
    existed, kept = p.exists(), None
    if existed:
        kept = f"_undo/{app}/{time.strftime('%Y%m%d-%H%M%S')}-{os.urandom(3).hex()}.txt"
        (root() / kept).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, root() / kept)
    p.write_text(text, encoding="utf-8")
    _look_beside(p, text)
    d = _dir(app)
    rel_n = str(p.relative_to(d)).replace("\\", "/")
    with LOCK:
        m = manifest(app)
        key = "pages" if rel_n.startswith("pages/") else "widgets" if rel_n.startswith("widgets/") else None
        if key and rel_n not in m[key]:
            m[key].append(rel_n)
        m["changed"] = time.time()
        _write(d / "app.json", json.dumps(m, indent=2))
    _changelog(d, who, ("changed " if existed else "added ") + rel_n + (f" (old text kept as {kept})" if kept else ""), why)
    _bump()
    return f"{'Changed' if existed else 'Added'} {app}/{rel_n}.", kept


def prune_undo(days: int = 60):
    """Old texts kept for Undo go after `days` (a change card that old is gone from the book too)."""
    cut = time.strftime("%Y%m%d-%H%M%S", time.localtime(time.time() - days * 86400))
    for f in (root() / "_undo").rglob("*.txt") if (root() / "_undo").is_dir() else []:
        if re.fullmatch(r"\d{8}-\d{6}", f.name[:15]) and f.name[:15] < cut:     # its name says when it was kept (copy2 keeps the old mtime)
            f.unlink(missing_ok=True)


def revert(app: str, rel: str, kept: str | None, who: str):
    """Undo of a write: the kept old text back, or a file the write added taken away."""
    p = _path(app, rel)
    d = _dir(app)
    rel_n = str(p.relative_to(d)).replace("\\", "/")
    if kept:
        src = (root() / kept).resolve()
        if (root() / "_undo").resolve() not in src.parents or not src.is_file():
            raise AppError(f"the old text of {rel_n} is gone")
        _write(p, src.read_text(encoding="utf-8"))
    else:
        p.unlink(missing_ok=True)
        with LOCK:
            m = manifest(app)
            for key in ("pages", "widgets"):
                if rel_n in m.get(key, []):
                    m[key].remove(rel_n)
            _write(d / "app.json", json.dumps(m, indent=2))
    _changelog(d, who, f"undid the change to {rel_n}", "Undo on the change card")
    _bump()


_WRITE_OPS = {sqlite3.SQLITE_INSERT, sqlite3.SQLITE_UPDATE, sqlite3.SQLITE_DELETE, sqlite3.SQLITE_DROP_TABLE, sqlite3.SQLITE_ALTER_TABLE,
              sqlite3.SQLITE_CREATE_TRIGGER, sqlite3.SQLITE_DROP_TRIGGER, sqlite3.SQLITE_CREATE_INDEX, sqlite3.SQLITE_DROP_INDEX}
_READ_PRAGMAS = {"table_info", "table_xinfo", "table_list", "index_list", "index_info", "foreign_key_list"}


def sql(app: str, statement: str, params=(), why: str = "", who: str = "agent", phone: bool = False, read_only: bool = False,
        rowid: bool = False) -> list | str | int:
    """One statement on the app's data.db. Agents can't write a phone table (only the facts ingester can: phone=True).
    rowid: a write answers with the row id its INSERT made instead of the OK line."""
    d = _dir(app)
    s = statement.strip().rstrip(";")
    if ";" in s:
        raise AppError("one statement at a time")
    con = sqlite3.connect(d / "data.db")
    if read_only:
        con.execute("PRAGMA query_only = ON")     # SQLite itself refuses every write
    if not phone:
        # SQLite names every table a statement really touches (triggers, CTEs and all): agents can't write a phone table,
        # attach another database or change settings.
        protected = set(manifest(app).get("phone_tables", []))

        def guard(op, a1, a2, _db, _src):
            if op in _WRITE_OPS and (a1 in protected or a2 in protected):
                return sqlite3.SQLITE_DENY
            # No triggers from agents: one would run later under the phone's own writes, past this guard.
            if op in (sqlite3.SQLITE_ATTACH, sqlite3.SQLITE_DETACH, sqlite3.SQLITE_CREATE_TRIGGER, sqlite3.SQLITE_CREATE_TEMP_TRIGGER):
                return sqlite3.SQLITE_DENY
            if op == sqlite3.SQLITE_PRAGMA and a1 not in _READ_PRAGMAS:
                return sqlite3.SQLITE_DENY
            return sqlite3.SQLITE_OK
        con.set_authorizer(guard)
    try:
        cur = con.execute(s, tuple(params))
        # A write is what SQLite says it is: no result columns (DML, DDL) or an open transaction (INSERT ... RETURNING),
        # never a guess from the first word (WITH ... SELECT reads).
        if cur.description is None or con.in_transaction:
            con.commit()
            if not phone:
                _changelog(d, who, f"data: {s[:80]}", why)
            _bump()
            return cur.lastrowid if rowid else f"OK, {cur.rowcount} row(s)."
        cols = [c[0] for c in cur.description or []]
        return [dict(zip(cols, r)) for r in cur.fetchmany(500)]
    except sqlite3.Error as e:
        raise AppError(f"SQL: {e}")
    finally:
        con.close()


def archive(app: str, why: str, who: str) -> str:
    d = _dir(app)
    a = root() / "_archive"
    a.mkdir(exist_ok=True)
    _changelog(d, who, "archived the app", why)
    shutil.move(str(d), str(a / app))
    _index()
    _bump()
    return f"Archived {app}. Restore puts it back exactly as it was."


def archived() -> list:
    """The archived apps, from their app.json: name, title, icon, about."""
    a = root() / "_archive"
    out = []
    for d in sorted(a.iterdir()) if a.is_dir() else []:
        if (d / "app.json").is_file():
            m = json.loads((d / "app.json").read_text(encoding="utf-8"))
            out.append({"name": d.name, "title": m.get("title", d.name), "icon": m.get("icon", ""), "about": m.get("about", "")})
    return out


def restore(app: str, who: str) -> str:
    if not NAME.match(app or ""):
        raise AppError(f"'{app}' isn't an app name")
    src = root() / "_archive" / app
    if not src.exists():
        raise AppError(f"{app} isn't archived")
    if (root() / app).exists():
        raise AppError(f"an app called {app} is already there")
    shutil.move(str(src), str(root() / app))
    _changelog(root() / app, who, "restored the app", "")
    _index()
    _bump()
    return f"Restored {app}."


_hashes: dict = {}     # path -> (mtime_ns, size, sha256): a phone-table row bumps the version, so most files are unchanged


def _sha(p: Path) -> str:
    st = p.stat()
    hit = _hashes.get(p)
    if hit and hit[:2] == (st.st_mtime_ns, st.st_size):
        return hit[2]
    h = hashlib.sha256(p.read_bytes()).hexdigest()
    _hashes[p] = (st.st_mtime_ns, st.st_size, h)
    return h


def phone_manifest() -> list:
    """Every file the phone mirrors (archived apps excluded), with its sha256. data.db goes too: pages read the phone
    tables from it, which is why a phone write bumps the version."""
    out = []
    for p in sorted(root().rglob("*")):
        rel = p.relative_to(root())
        if not p.is_file() or rel.parts[0] in ("_archive", "_undo") or rel.name.startswith(".") or rel.name.endswith(("-wal", "-shm")):
            continue
        out.append({"app": rel.parts[0] if len(rel.parts) > 1 else "", "path": "/".join(rel.parts[1:]) if len(rel.parts) > 1 else rel.name,
                    "sha256": _sha(p), "size": p.stat().st_size})
    return out


def phone_file(app: str, rel: str) -> Path:
    p = (root() / app / rel).resolve() if app else (root() / rel).resolve()
    if root().resolve() not in p.parents or {"_archive", "_undo"} & set(p.parts) or not p.is_file():
        raise AppError("no such file")
    return p
