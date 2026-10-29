"""Messenger bits on Owner's side: his reactions, edits, deletes and uploads, and how each agent hears of them.

They are light events: Athena reads them as a short line at the top of her next turn in that thread (never a run of
their own); Iris and Atlas get a channel event marked with its kind. Nothing here relays in the group, so a reaction
can't start an agents' back-and-forth.
"""
import os
import re
import secrets
import shutil
import tempfile
import threading

from . import config

AGENTS = ("athena", "iris", "atlas")
QUICK = 160   # how much of a message a light event quotes
_copying = threading.Lock()   # a copy and a delete's cleanup never overlap


def _said(m):
    from .store import defang
    return defang(" ".join(m["text"].split())[:QUICK])


def _tell(store, bus, agent, thread, kind, line):
    if agent == "athena":
        store.note(thread, "athena", line)
    else:
        bus.event(agent, thread, kind, line)


def reacted(store, bus, mid, emoji, on):
    """His reaction (or taking it back) reaches the agent who wrote the message, and only that one."""
    m = store.message(mid)
    store.react(mid, "owner", emoji, on)
    store.send("reaction", {"thread": m["thread"], "message": mid, "who": "owner", "emoji": emoji, "on": on})
    if m["sender"] in AGENTS and on:
        _tell(store, bus, m["sender"], m["thread"], "reaction", f"[Owner reacted {emoji} to your message {mid}: {_said(m)}]")


def _heard_by(store, m):
    """The agents that already read this message (an edit before they read it needs no word: they get the new text)."""
    return [a for a in AGENTS if store.was_delivered(m["id"], a)]


def edited(store, bus, mid, text):
    m = store.message(mid)
    store.edit(mid, text)
    store.send("edit", {"thread": m["thread"], "message": mid, "text": text})
    for a in _heard_by(store, m):
        _tell(store, bus, a, m["thread"], "edit", f"[Owner edited his message {mid}. It now says: {' '.join(text.split())[:1000]}]")


def deleted(store, bus, mid):
    m = store.message(mid)
    store.delete(mid)
    store.send("delete", {"thread": m["thread"], "message": mid})
    for a in _heard_by(store, m):
        _tell(store, bus, a, m["thread"], "delete", f"[Owner deleted his message {mid} ({_said(m)}). Don't act on it.]")


def save_upload(store, name, mime, body):
    """His file, kept under the workspace's uploads/ (carried over from Zeus). Returns the upload id."""
    d = config.WORKSPACE / "uploads"
    d.mkdir(parents=True, exist_ok=True)
    safe = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._")[:80] or "file"
    mime = mime if re.fullmatch(r"[a-z]+/[a-z0-9.+-]+", mime or "") else "application/octet-stream"
    uid = store.add_upload(safe, mime, len(body), "")
    p = d / f"{uid}-{safe}"
    p.write_bytes(body)
    store.x("UPDATE uploads SET path = ? WHERE id = ?", (str(p), uid))
    return uid


def _agent_dir(agent):
    return (config.BOX_FILES, config.BOX_FILES_SEEN) if agent == "athena" else (config.AGENT_FILES, str(config.AGENT_FILES))


def agent_file(f, agent):
    """A copy of his attached file where this agent's own file tools open it (images show as images there); the path
    as the agent sees it, or None while that folder isn't there or the upload is gone. Written as a fresh temp file then
    moved in (mkstemp: O_EXCL, never through a planted link); the folder's setgid group lets the agent read it (0640).
    The folder is traversable, not listable (2710), and each copy's name carries a random part, so only the message
    line that gives the path finds it."""
    d, seen = _agent_dir(agent)
    if d.is_symlink() or not d.is_dir():
        return None
    with _copying:
        have = [p for p in d.glob(f"{f['id']}-*") if re.match(r"\d+-[0-9a-f]{32}-", p.name) and p.is_file() and not p.is_symlink()]
        if have:
            return f"{seen}/{have[0].name}"
        safe = re.sub(r"[^A-Za-z0-9._-]+", "_", f["name"]).strip("._")[:80] or "file"
        name = f"{f['id']}-{secrets.token_hex(16)}-{safe}"
        try:
            src = os.open(f["path"], os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        except OSError:
            return None
        fd, tmp = tempfile.mkstemp(dir=d, prefix=".copy-")
        try:
            with os.fdopen(src, "rb") as i, os.fdopen(fd, "wb") as o:
                shutil.copyfileobj(i, o)
            os.chmod(tmp, 0o640)
            if not os.path.isfile(f["path"]):     # deleted while copying: publish nothing
                return None
            os.replace(tmp, d / name)
        finally:
            if os.path.lexists(tmp):
                os.unlink(tmp)
    return f"{seen}/{name}"


def drop_agent_files(f):
    """His upload is gone: so are the agents' copies (after any copy in flight lands, so none outlives it)."""
    with _copying:
        for agent in ("athena", "iris"):
            d, _ = _agent_dir(agent)
            for p in d.glob(f"{f['id']}-*") if d.is_dir() and not d.is_symlink() else []:
                p.unlink(missing_ok=True)
