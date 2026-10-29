"""apollo.db: threads and messages, the phone's facts, the outbox to the phone, status rows, usage and settings."""
import contextlib
import json
import re
import sqlite3
import threading
import time
import unicodedata
from pathlib import Path

from . import config

SCHEMA = """
CREATE TABLE IF NOT EXISTS threads (id INTEGER PRIMARY KEY, title TEXT NOT NULL, created REAL NOT NULL, updated REAL NOT NULL,
    session TEXT, kind TEXT NOT NULL DEFAULT 'chat');
CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY, thread INTEGER NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL,
    at REAL NOT NULL, to_agent TEXT, delivered INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS messages_thread ON messages(thread, id);
CREATE TABLE IF NOT EXISTS facts (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, at REAL NOT NULL, data TEXT NOT NULL,
    urgent INTEGER NOT NULL, sent INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS facts_unsent ON facts(sent, id);
CREATE TABLE IF NOT EXISTS outbox (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, data TEXT NOT NULL, at REAL NOT NULL, acked INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS actions (id INTEGER PRIMARY KEY, outbox INTEGER, ok INTEGER, detail TEXT, at REAL);
CREATE TABLE IF NOT EXISTS status (component TEXT PRIMARY KEY, ok INTEGER NOT NULL, detail TEXT NOT NULL, checked REAL NOT NULL);
CREATE TABLE IF NOT EXISTS usage (session TEXT PRIMARY KEY, month TEXT NOT NULL, list_cost REAL NOT NULL, at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS fcm (token TEXT PRIMARY KEY, at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, session TEXT NOT NULL, thread INTEGER, type TEXT NOT NULL, at REAL NOT NULL, data TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS events_session ON events(session, id);
CREATE TABLE IF NOT EXISTS deliveries (message INTEGER NOT NULL, agent TEXT NOT NULL, at REAL NOT NULL, PRIMARY KEY(message, agent));
CREATE TABLE IF NOT EXISTS log (id INTEGER PRIMARY KEY, at REAL NOT NULL, who TEXT NOT NULL, what TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS alerts (id INTEGER PRIMARY KEY, at REAL NOT NULL, source TEXT NOT NULL, level TEXT NOT NULL, text TEXT NOT NULL,
    resolved REAL, resolved_by TEXT, note TEXT);
CREATE TABLE IF NOT EXISTS reactions (message INTEGER NOT NULL, who TEXT NOT NULL, emoji TEXT NOT NULL, at REAL NOT NULL, PRIMARY KEY(message, who, emoji));
CREATE TABLE IF NOT EXISTS uploads (id INTEGER PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, path TEXT NOT NULL, at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, thread INTEGER NOT NULL, agent TEXT NOT NULL, text TEXT NOT NULL, at REAL NOT NULL, used INTEGER NOT NULL DEFAULT 0);
"""


def mentions(text):
    """@athena / @iris / @atlas in a group message: the agents it nudges to answer."""
    low = text.lower()
    return [a for a in ("athena", "iris", "atlas") if re.search(rf"@{a}\b", low)]


# Who a message is from, stamped by the path it came in on (Owner, 2026-10-28: everyone knows what came from whom).
# owner: only the phone's chat (main._chat). claude: Claude Code on his laptop or the server, acting for him (apollo.say).
# apollo: Apollo itself (facts, his taps, alerts, handoffs, action results). The agents post as themselves (tools.py).
SENDERS = {"owner": "Owner", "claude": "Claude Code", "apollo": "Apollo", "athena": "Athena", "iris": "Iris", "atlas": "Atlas"}
HUMAN = ("owner", "claude")     # Owner, or a helper relaying for him: the agents answer these, not each other
# Look-alikes of the letters in the sender names (Cyrillic, Greek, Latin extras, digits), folded before matching.
_LOOKS = str.maketrans({"а": "a", "ɑ": "a", "α": "a", "с": "c", "ϲ": "c", "ԁ": "d", "е": "e", "ε": "e", "һ": "h", "н": "h",
                        "і": "i", "ı": "i", "ι": "i", "ɩ": "i", "ӏ": "l", "1": "l", "|": "l", "η": "n", "п": "n", "о": "o",
                        "ο": "o", "σ": "o", "0": "o", "р": "p", "ρ": "p", "г": "r", "ѕ": "s", "т": "t", "τ": "t", "υ": "u", "ս": "u"})


def _fold(text):
    """The letters a reader sees: NFKC, lower case, no format characters (zero-width and the like) or combining marks,
    look-alikes folded, leading spaces gone."""
    text = unicodedata.normalize("NFKC", text).lower()
    return "".join(c for c in text if unicodedata.category(c) not in ("Cf", "Mn", "Me")).translate(_LOOKS).lstrip()


def _opens(c):
    """[ or anything bracket-like: an opening bracket of any script or width (Unicode Ps), or <."""
    return any(unicodedata.category(x) == "Ps" or x == "<" for x in unicodedata.normalize("NFKC", c))


def defang(text):
    """A message's own text can't pass for a sender label: "[owner]" (or "[Owner reacted ...") inside it becomes "(owner]".
    Zero-width characters, fullwidth or other brackets and look-alike letters ("［hаrsha]") don't get past it."""
    out = list(text)
    for i, c in enumerate(text):
        if c != "(" and _opens(c):
            ahead = _fold(text[i + 1:i + 60])
            if any(ahead.startswith(n) and not ahead[len(n):len(n) + 1].isalnum() for n in SENDERS):
                out[i] = "("
    return "".join(out)


def inquote(text):
    """Someone's words, one line inside « » (a marker in the text itself is swapped so it can't close the quote)."""
    return "«" + " ".join(text.split()).replace("«", "‹").replace("»", "›") + "»"


class Store:
    def __init__(self, path=None):
        self.path = str(path or config.DB)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)
        # Each conversation belongs to one agent (Owner, 2026-10-26: Iris gets her own, completely separate chat).
        if "agent" not in {r["name"] for r in self.q("PRAGMA table_info(threads)")}:
            self.db.execute("ALTER TABLE threads ADD COLUMN agent TEXT NOT NULL DEFAULT 'athena'")
        # The vault a thread's session was made with (its MCP token): a session is remade when its kind's vault changes.
        if "vault" not in {r["name"] for r in self.q("PRAGMA table_info(threads)")}:
            self.db.execute("ALTER TABLE threads ADD COLUMN vault TEXT")
        # His archive: a conversation he put away (hidden on the phone, never unread) until a new message lands in it.
        if "archived" not in {r["name"] for r in self.q("PRAGMA table_info(threads)")}:
            self.db.execute("ALTER TABLE threads ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        # Answer buttons under a message (thread_post choices): a JSON list of labels.
        if "choices" not in {r["name"] for r in self.q("PRAGMA table_info(messages)")}:
            self.db.execute("ALTER TABLE messages ADD COLUMN choices TEXT")
        # Messenger bits: the message it quotes, his edits and deletes (a tombstone), an attached upload.
        have = {r["name"] for r in self.q("PRAGMA table_info(messages)")}
        for col, decl in (("reply_to", "INTEGER"), ("edited", "REAL"), ("deleted", "INTEGER NOT NULL DEFAULT 0"), ("upload", "INTEGER"),
                          ("source", "TEXT")):
            if col not in have:
                self.db.execute(f"ALTER TABLE messages ADD COLUMN {col} {decl}")
        # The phone's own id for a fact ("<install>:<row>"): a batch sent again after a lost answer adds nothing twice.
        if "cid" not in {r["name"] for r in self.q("PRAGMA table_info(facts)")}:
            self.db.execute("ALTER TABLE facts ADD COLUMN cid TEXT")
        self.db.execute("CREATE UNIQUE INDEX IF NOT EXISTS facts_cid ON facts(cid)")
        self._recover_unaddressed()
        self.after_send = None     # main wakes the phone (FCM) after anything lands in the outbox: after_send(kind)
        self.phone_seen = 0.0      # when the phone last fetched (/v1/sync or /v1/activity)
        self._act: dict = {}       # agent -> (last push time, held activity, timer) for activity()

    def _recover_unaddressed(self):
        """Facts, heartbeats and routines used to be posted with no to_agent, so Athena never got them. Address them to her
        (once: new ones carry it); a stale heartbeat asks nothing, so all but the newest count as seen."""
        with self.lock:
            rows = self.q("SELECT m.id, m.text FROM messages m JOIN threads t ON t.id = m.thread WHERE m.to_agent IS NULL "
                          "AND m.sender = 'apollo' AND t.kind IN ('facts', 'routines') ORDER BY m.id")
            if not rows:
                return
            self.db.executemany("UPDATE messages SET to_agent = 'athena' WHERE id = ?", [(r["id"],) for r in rows])
            beats = [r["id"] for r in rows if r["text"].startswith("HEARTBEAT ")][:-1]
            self.db.executemany("INSERT OR IGNORE INTO deliveries(message, agent, at) VALUES(?, 'athena', ?)", [(i, time.time()) for i in beats])

    def q(self, sql, args=()):
        with self.lock:
            return [dict(r) for r in self.db.execute(sql, args).fetchall()]

    def x(self, sql, args=()):
        with self.lock:
            return self.db.execute(sql, args).lastrowid

    @contextlib.contextmanager
    def tx(self):
        """Everything written inside lands together or not at all (this thread holds the lock till then)."""
        with self.lock:
            self.db.execute("BEGIN")
            try:
                yield
            except BaseException:
                self.db.execute("ROLLBACK")
                raise
            self.db.execute("COMMIT")

    # ---- settings and log ----
    def setting(self, name):
        rows = self.q("SELECT value FROM settings WHERE name = ?", (name,))
        return json.loads(rows[0]["value"]) if rows else config.DEFAULTS.get(name)

    def set_setting(self, name, value):
        self.x("INSERT INTO settings(name, value) VALUES(?, ?) ON CONFLICT(name) DO UPDATE SET value = excluded.value", (name, json.dumps(value)))

    def log(self, who, what):
        self.x("INSERT INTO log(at, who, what) VALUES(?, ?, ?)", (time.time(), who, what[:500]))

    # ---- threads ----
    def thread(self, title, kind="chat", agent="athena"):
        now = time.time()
        return self.x("INSERT INTO threads(title, created, updated, kind, agent) VALUES(?, ?, ?, ?, ?)", (title[:120] or "Chat", now, now, kind, agent))

    def thread_agent(self, thread):
        rows = self.q("SELECT agent FROM threads WHERE id = ?", (thread,))
        return rows[0]["agent"] if rows else "athena"

    def facts_thread(self):
        rows = self.q("SELECT id FROM threads WHERE kind = 'facts'")
        return rows[0]["id"] if rows else self.thread("What the phone noticed", "facts")

    def taps_thread(self):
        rows = self.q("SELECT id FROM threads WHERE kind = 'taps'")
        return rows[0]["id"] if rows else self.thread("Your taps", "taps")

    def post(self, thread, sender, text, to_agent=None, choices=None, reply_to=None, upload=None, source=None):
        """source: "voice" when his words came from speech on the phone (render tags them)."""
        if sender not in SENDERS:
            raise ValueError(f"no sender {sender!r}")
        now = time.time()
        mid = self.x("INSERT INTO messages(thread, sender, text, at, to_agent, choices, reply_to, upload, source) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                     (thread, sender, text, now, to_agent, json.dumps(choices) if choices else None, reply_to, upload, source))
        self.x("UPDATE threads SET updated = ?, archived = 0 WHERE id = ?", (now, thread))
        return mid

    # ---- who has seen what: one row per message and agent (a group message reaches each agent on its own) ----
    def delivered(self, mid, agent):
        self.x("INSERT OR IGNORE INTO deliveries(message, agent, at) VALUES(?, ?, ?)", (mid, agent, time.time()))

    def undelivered(self, agent):
        """Messages waiting for this agent: addressed to it, or in the group chat (from anyone but itself)."""
        return self.q("SELECT m.id, m.thread FROM messages m JOIN threads t ON t.id = m.thread WHERE m.sender != ? "
                      "AND (m.to_agent = ? OR (t.agent = 'group' AND m.to_agent IS NULL)) "
                      "AND NOT EXISTS (SELECT 1 FROM deliveries d WHERE d.message = m.id AND d.agent = ?) ORDER BY m.id", (agent, agent, agent))

    def agent_run(self, thread):
        """How many messages in a row the agents have sent each other in this thread since Owner last wrote."""
        n = 0
        for r in self.q("SELECT sender FROM messages WHERE thread = ? ORDER BY id DESC LIMIT 200", (thread,)):
            if r["sender"] in HUMAN:
                break
            n += 1
        return n

    def threads(self):
        return self.q("SELECT t.id, t.title, t.updated, t.kind, t.agent, t.archived, (SELECT text FROM messages m WHERE m.thread = t.id ORDER BY id DESC LIMIT 1) AS last "
                      "FROM threads t ORDER BY updated DESC")

    def archive(self, thread, on):
        """His conversations only (chat or carried over from Zeus); False when there is no such thread."""
        if not self.q("SELECT 1 FROM threads WHERE id = ? AND kind IN ('chat', 'zeus')", (thread,)):
            return False
        self.x("UPDATE threads SET archived = ? WHERE id = ?", (1 if on else 0, thread))
        return True

    def messages(self, thread, since=0, limit=200, before=0):
        """Oldest first. since: the ones after it (catching up). Otherwise the newest page, or the page just older than
        `before` (scrolling back; the first id of a page is the next `before`)."""
        cols = "SELECT id, sender, text, at, to_agent, choices, reply_to, edited, deleted, upload FROM messages WHERE thread = ?"
        if since:
            rows = self.q(cols + " AND id > ? ORDER BY id LIMIT ?", (thread, since, limit))
        else:
            rows = self.q(cols + " AND id < ? ORDER BY id DESC LIMIT ?", (thread, before or 2 ** 62, limit))[::-1]
        reacts = {}
        ids = [r["id"] for r in rows] or [0]
        for r in self.q(f"SELECT message, who, emoji FROM reactions WHERE message IN ({','.join('?' * len(ids))}) ORDER BY at", ids):
            reacts.setdefault(r["message"], []).append({"who": r["who"], "emoji": r["emoji"]})
        return [dict(r, choices=json.loads(r["choices"]) if r["choices"] else [], reactions=reacts.get(r["id"], []),
                     file=self.upload(r["upload"]) if r["upload"] else None) for r in rows]

    # ---- messenger bits: reactions, quotes, edits, deletes, uploads, light notes for an agent's next turn ----
    def message(self, mid):
        rows = self.q("SELECT id, thread, sender, text, at, to_agent, reply_to, deleted, upload, source FROM messages WHERE id = ?", (mid,))
        return rows[0] if rows else None

    def react(self, mid, who, emoji, on=True):
        if on:
            self.x("INSERT OR IGNORE INTO reactions(message, who, emoji, at) VALUES(?, ?, ?, ?)", (mid, who, emoji, time.time()))
        else:
            self.x("DELETE FROM reactions WHERE message = ? AND who = ? AND emoji = ?", (mid, who, emoji))

    def edit(self, mid, text):
        self.x("UPDATE messages SET text = ?, edited = ? WHERE id = ?", (text, time.time(), mid))

    def delete(self, mid):
        """A tombstone: the row stays (quotes and order hold), its words and file go."""
        up = (self.q("SELECT upload FROM messages WHERE id = ?", (mid,)) or [{}])[0].get("upload")
        self.x("UPDATE messages SET text = '', deleted = 1, upload = NULL, choices = NULL WHERE id = ?", (mid,))
        self.x("DELETE FROM reactions WHERE message = ?", (mid,))
        if up and not self.q("SELECT 1 FROM messages WHERE upload = ?", (up,)):
            f = self.upload(up)
            self.x("DELETE FROM uploads WHERE id = ?", (up,))
            if f:
                Path(f["path"]).unlink(missing_ok=True)
                from .chat import drop_agent_files
                drop_agent_files(f)

    def add_upload(self, name, mime, size, path):
        return self.x("INSERT INTO uploads(name, mime, size, path, at) VALUES(?, ?, ?, ?, ?)", (name, mime, size, path, time.time()))

    def upload(self, uid):
        rows = self.q("SELECT id, name, mime, size, path FROM uploads WHERE id = ?", (uid,))
        return rows[0] if rows else None

    def was_delivered(self, mid, agent):
        return bool(self.q("SELECT 1 FROM deliveries WHERE message = ? AND agent = ?", (mid, agent)))

    def note(self, thread, agent, text):
        self.x("INSERT INTO notes(thread, agent, text, at) VALUES(?, ?, ?, ?)", (thread, agent, text[:500], time.time()))

    def take_notes(self, thread, agent):
        """Light events (his reactions, edits, deletes) waiting for the agent's next turn in this thread; read once."""
        with self.lock:
            rows = self.q("SELECT id, text FROM notes WHERE thread = ? AND agent = ? AND used = 0 ORDER BY id", (thread, agent))
            self.db.executemany("UPDATE notes SET used = 1 WHERE id = ?", [(r["id"],) for r in rows])
        return [r["text"] for r in rows]

    def render(self, m, agent=None):
        """A message as an agent reads it: the quote it replies to, who an @mention is for, the attached file (for an
        agent with file tools, a copy at a path it opens itself). The sender's label goes on by the caller ([owner] for
        Athena, the from attribute for Iris and Atlas); only Apollo's own posts keep a label-like "[name" in their text."""
        text = "(deleted)" if m.get("deleted") else m["text"]
        if m["sender"] != "apollo":
            text = defang(text)
        if m.get("reply_to"):
            q = self.message(m["reply_to"])
            if q:
                said = "(deleted)" if q["deleted"] else inquote(defang(" ".join(q["text"].split())[:160]))
                text = f"[replying to {SENDERS.get(q['sender'], q['sender'])}: {said}]\n{text}"
        if m["sender"] in HUMAN and self.thread_agent(m["thread"]) == "group":
            named = mentions(m["text"])
            if named:
                text = f"[for: {', '.join(named)}]\n{text}"
        if m.get("upload"):
            f = self.upload(m["upload"])
            from .chat import agent_file
            at = agent_file(f, agent) if f and agent else None
            if at:
                what = "photo" if f["mime"].startswith("image/") else "file"
                tool = {"athena": "read", "atlas": "view_image"}.get(agent, "Read")     # Atlas is Codex
                text += f"\n[attached: {what} at {at} ({f['mime']}, {f['size']} bytes): open it with your {tool} tool]"
            elif f:
                text += f"\n[attached: {f['name']} ({f['mime']}, {f['size']} bytes), upload {f['id']}: read it with upload_read]"
        if m.get("source") == "voice":
            text = "[by voice: speech heard by the phone, it can be another voice or misheard; confirm with him before anything outward]\n" + text
        return text

    # ---- facts ----
    def add_fact(self, kind, at, data, urgent, cid=None):
        """The new row's id, or None when a fact with this cid is already here."""
        with self.lock:
            cur = self.db.execute("INSERT OR IGNORE INTO facts(kind, at, data, urgent, cid) VALUES(?, ?, ?, ?, ?)",
                                  (kind, at, json.dumps(data), 1 if urgent else 0, cid))
            return cur.lastrowid if cur.rowcount else None

    def unsent_facts(self, urgent_only=False):
        sql = "SELECT id, kind, at, data, urgent FROM facts WHERE sent = 0" + (" AND urgent = 1" if urgent_only else "") + " ORDER BY id"
        return [dict(r, data=json.loads(r["data"])) for r in self.q(sql)]

    def mark_sent(self, ids):
        with self.lock:
            self.db.executemany("UPDATE facts SET sent = 1 WHERE id = ?", [(i,) for i in ids])

    # ---- outbox ----
    def send(self, kind, data):
        if kind == "message" and "thread" in data:     # the phone files a thread it hasn't seen as a conversation or a system one, with its title
            t = (self.q("SELECT kind, title FROM threads WHERE id = ?", (data["thread"],)) or [{"kind": "chat", "title": "Chat"}])[0]
            data = {"title": t["title"], **data, "kind": t["kind"]}
        oid = self.x("INSERT INTO outbox(kind, data, at) VALUES(?, ?, ?)", (kind, json.dumps(data), time.time()))
        if self.after_send:
            self.after_send(kind)
        return oid

    def outbox(self, since):
        return [dict(r, data=json.loads(r["data"])) for r in self.q("SELECT id, kind, data FROM outbox WHERE id > ? AND acked = 0 ORDER BY id LIMIT 200", (since,))]

    def ack(self, ids):
        with self.lock:
            self.db.executemany("UPDATE outbox SET acked = 1 WHERE id = ?", [(i,) for i in ids])

    # ---- what each agent is doing right now (the phone's pixel characters) ----
    ACTIVITY_GAP = 2.0

    def activity(self, agent, tool, server=""):
        """The tool, connector or event an agent is on, and nothing else (no args, no text). At most one push per agent
        every ACTIVITY_GAP seconds; a held one goes out when the gap ends. idle always goes at once."""
        a = {"agent": agent, "tool": str(tool)[:80], "server": str(server or "")[:80], "at": time.time()}
        latest = self.setting("activity") or {}
        self.set_setting("activity", dict(latest, **{agent: a}))
        with self.lock:
            last, _, timer = self._act.get(agent, (0.0, None, None))
            wait = last + self.ACTIVITY_GAP - a["at"]
            if tool != "idle" and wait > 0:
                if not timer:
                    timer = threading.Timer(wait, self._activity_flush, (agent,))
                    timer.daemon = True
                    timer.start()
                self._act[agent] = (last, a, timer)
                return
            if timer:
                timer.cancel()
            self._act[agent] = (a["at"], None, None)
        self.send("activity", a)

    def _activity_flush(self, agent):
        with self.lock:
            _, held, _ = self._act.get(agent, (0.0, None, None))
            self._act[agent] = (time.time(), None, None)
        if held:
            self.send("activity", held)

    # ---- every event of every Athena session, kept in full (Owner: see and capture everything) ----
    def event(self, session, thread, kind, data):
        self.x("INSERT INTO events(session, thread, type, at, data) VALUES(?, ?, ?, ?, ?)", (session, thread, kind, time.time(), data))

    # ---- status and usage ----
    def set_status(self, component, ok, detail="", alert=True):
        """alert=False: a state that says so itself (the brain held for the month), no popup."""
        from . import alerts
        if ok:
            alerts.clear(self, "status:" + component)
        elif alert:
            alerts.down(self, component, detail)
        # A component going down opens an alert; coming back resolves it (Cerberus, simplified).
        was = self.q("SELECT ok FROM status WHERE component = ?", (component,))
        if not ok and (not was or was[0]["ok"]):
            self.alert(component, "normal", f"{component} down: {detail}"[:300])
        elif ok and was and not was[0]["ok"]:
            self.x("UPDATE alerts SET resolved = ?, resolved_by = 'apollo', note = ? WHERE source = ? AND resolved IS NULL", (time.time(), detail[:300], component))
        self.x("INSERT INTO status VALUES(?, ?, ?, ?) ON CONFLICT(component) DO UPDATE SET ok = excluded.ok, detail = excluded.detail, checked = excluded.checked",
               (component, 1 if ok else 0, detail[:300], time.time()))

    def alert(self, source, level, text):
        """A new alert. High ones go straight to his phone (they don't wait for Athena)."""
        aid = self.x("INSERT INTO alerts(at, source, level, text) VALUES(?, ?, ?, ?)", (time.time(), source, level, text[:500]))
        if level == "high":
            self.send("notify", {"title": f"Alert: {source}", "body": text[:500], "priority": "high", "from": "apollo"})
        return aid

    def alerts(self, include_resolved=False, limit=100):
        return self.q("SELECT * FROM alerts" + ("" if include_resolved else " WHERE resolved IS NULL") + " ORDER BY id DESC LIMIT ?", (limit,))

    def resolve_alert(self, aid, who, note=""):
        """True when an open alert was resolved."""
        with self.lock:
            return self.db.execute("UPDATE alerts SET resolved = ?, resolved_by = ?, note = ? WHERE id = ? AND resolved IS NULL", (time.time(), who, note[:300], aid)).rowcount > 0

    def held_brain(self):
        """Who holds the brain (brain.Brain.hold): iris or paused until the month it happened is over, else athena."""
        held = self.setting("brain_hold") or {}
        return held.get("brain", "athena") if held.get("month") == time.strftime("%Y-%m") else "athena"

    def statuses(self):
        return self.q("SELECT component, ok, detail, checked FROM status ORDER BY component")

    def record_usage(self, session, list_cost):
        self.x("INSERT INTO usage VALUES(?, ?, ?, ?) ON CONFLICT(session) DO UPDATE SET list_cost = excluded.list_cost, at = excluded.at",
               (session, time.strftime("%Y-%m"), float(list_cost), time.time()))

    def month_cost(self, month=None):
        return self.q("SELECT COALESCE(SUM(list_cost), 0) AS c FROM usage WHERE month = ?", (month or time.strftime("%Y-%m"),))[0]["c"]
