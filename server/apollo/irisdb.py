"""Read-only owner.db for the agents (Zeus's query_iris_db / iris_db_schema; zeus-agents's db-schema and db-dump).
Iris owns owner.db and changes it as she likes. Apollo opens it read-only (mode=ro; deploy/iris-db-read.sh gives the
apollo user read access) and builds a filtered copy in memory: secret-shaped tables and columns never come in, balance
rows and columns never do, card- and account-shaped cells keep their last four digits, long digit runs are cut, and a
task in a private context reads "Private task". The agent's one SELECT runs on that copy only (as Zeus ran it on the
phone's copy), so no WHERE, aggregate or substr() can reach what was dropped. Writes stay Iris's: ask_iris.
"""
import os
import re
import sqlite3
import stat
import threading
import time
from pathlib import Path

PATH = Path(os.environ.get("APOLLO_IRIS_DB", "/home/agents/owner.db"))
ROWS = 200            # rows back: protects her context
TABLE_ROWS = 50_000   # newest rows per table in the copy: protects the server's memory
FRESH_S = 600         # the copy is reused this long
QUERY_S = 5

SECRET_NAME = re.compile(r"(?i)(^|_)(key|keys|token|tokens|secret|secrets|password|passwords|passwd|pin|otp|cookie|cookies|credential|credentials|session|sessions)($|_)")
NAME_OK = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,63}")
LONG_DIGITS = re.compile(r"(?<!\d)(?!\d{4}-\d{2}-\d{2})\d(?:[ -]?\d){6,}")
CARD_DIGITS = re.compile(r"\d(?:[ -]?\d){4,}")
CARD_COL = re.compile(r"(?i)(^|_)(card|cc|acct|account|iban|routing|ssn)s?(_|$)")
KIND_COL = re.compile(r"(?i)^(type|kind|category)$")
BALANCE = re.compile(r"(?i)balance")
SENSITIVE = re.compile(r"money|financ|bank|loan|card|tax|legal|court|lawyer|health|medical|doctor|weight|therapy", re.I)
TASK_TABLES = ("tasks", "task", "todos", "todo")
_copy = {"db": None, "at": 0.0, "schema": []}
_lock = threading.Lock()


class Unavailable(Exception):
    """owner.db can't be read here (missing, no permission); the text is safe to show."""


def qi(name):
    return '"' + str(name).replace('"', '""') + '"'


def cell(col, v):
    if isinstance(v, bytes):
        return None
    if v is not None and CARD_COL.search(col) and not isinstance(v, float):
        return CARD_DIGITS.sub(lambda m: re.sub(r"\D", "", m.group())[-4:], str(v))
    return LONG_DIGITS.sub("", v) if isinstance(v, str) else v


def clean_row(table, cols, row):
    d = dict(zip(cols, row))
    if any(KIND_COL.match(c) and BALANCE.search(str(d[c] or "")) for c in cols):
        return None
    if table in TASK_TABLES and any(SENSITIVE.search(str(d.get(c) or "")) for c in ("context", "project", "category", "tags", "area", "list")):
        for c in ("title", "name", "task", "summary"):
            if c in d:
                d[c] = "Private task"
        for c in ("notes", "note", "details", "description"):
            if c in d:
                d[c] = ""
    return [cell(c, d[c]) for c in cols]


def _ino(fd_name):
    try:
        st = os.stat(f"/proc/self/fd/{fd_name}")
        return st.st_dev, st.st_ino
    except OSError:
        return None


def _open(path):
    """owner.db's folder is agents's, and sqlite opens by name (following links): a link there must never hand the
    agents a file of the server's own (apollo.db, an app's data.db). The file is checked through a no-follow fd (a
    regular file, owned by the folder's owner), and the file sqlite then holds open must be that same one."""
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except OSError as e:
        raise Unavailable(f"owner.db can't be read here ({type(e).__name__}: a link, or missing); see deploy/iris-db-read.sh") from None
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_uid != os.stat(path.parent).st_uid:
            raise Unavailable("owner.db here isn't Iris's own file")
        src = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True, timeout=5)
        src.execute("SELECT 1 FROM sqlite_master LIMIT 1").fetchall()
        if os.path.isdir("/proc/self/fd") and not any(n != str(fd) and _ino(n) == (st.st_dev, st.st_ino) for n in os.listdir("/proc/self/fd")):
            src.close()
            raise Unavailable("owner.db changed while it was opened")
        return src
    finally:
        os.close(fd)


def _build(path):
    try:
        src = _open(path)
        master = list(src.execute("SELECT name, COALESCE(sql, '') FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"))
    except sqlite3.Error as e:
        raise Unavailable(f"owner.db can't be read here ({e}); see deploy/iris-db-read.sh") from None
    mem = sqlite3.connect(":memory:", check_same_thread=False)
    schema = []
    try:
        virtual = [n for n, q in master if q.upper().startswith("CREATE VIRTUAL TABLE")]
        for name, _ in master:
            if SECRET_NAME.search(name) or not NAME_OK.fullmatch(name) or name in virtual or any(name.startswith(v + "_") for v in virtual):
                continue
            info = [r for r in src.execute(f"PRAGMA table_info({qi(name)})") if not SECRET_NAME.search(r[1]) and not BALANCE.search(r[1])]
            if not info:
                continue
            cols = [r[1] for r in info]
            mem.execute(f"CREATE TABLE {qi(name)} (" + ", ".join(f"{qi(r[1])} {r[2] or ''}" for r in info) + ")")
            sel = ", ".join(qi(c) for c in cols)
            rows = [clean_row(name, cols, list(r)) for r in src.execute(f"SELECT {sel} FROM (SELECT * FROM {qi(name)} ORDER BY rowid DESC LIMIT {TABLE_ROWS})")]
            rows = [r for r in rows if r is not None]
            mem.executemany(f"INSERT INTO {qi(name)} VALUES ({', '.join('?' * len(cols))})", rows)
            schema.append({"table": name, "columns": {r[1]: r[2] for r in info}, "rows": len(rows)})
        mem.commit()
    finally:
        src.close()
    return mem, schema


def copy(path=None, now=None):
    now = now or time.time()
    with _lock:
        if _copy["db"] is None or now - _copy["at"] > FRESH_S or path:
            if _copy["db"] is not None:
                _copy["db"].close()
            _copy["db"], _copy["schema"] = _build(Path(path or PATH))
            _copy["at"] = now
        return _copy["db"], _copy["schema"]


def schema(path=None):
    return copy(path)[1]


def refused(q: str):
    s = q.strip().rstrip(";")
    if not s:
        return "no SQL"
    if ";" in s:
        return "one statement only"
    if not re.match(r"(?is)^\s*(select|with)\b", s):
        return "only SELECT (or WITH ... SELECT)"
    return None


def query(q: str, path=None):
    why = refused(q)
    if why:
        raise ValueError(why)
    db, _ = copy(path)

    def guard(op, *_):
        return sqlite3.SQLITE_OK if op in (sqlite3.SQLITE_SELECT, sqlite3.SQLITE_READ, sqlite3.SQLITE_FUNCTION, sqlite3.SQLITE_RECURSIVE) else sqlite3.SQLITE_DENY
    until = time.monotonic() + QUERY_S
    with _lock:
        db.set_authorizer(guard)
        db.set_progress_handler(lambda: 1 if time.monotonic() > until else 0, 10_000)
        try:
            cur = db.execute(q.strip().rstrip(";"))
            cols = [c[0] for c in cur.description or []]
            return [dict(zip(cols, r)) for r in cur.fetchmany(ROWS)]
        except sqlite3.Error as e:
            raise ValueError(f"SQL: {e}") from None
        finally:
            db.set_authorizer(None)
            db.set_progress_handler(None, 0)
