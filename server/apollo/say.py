"""Claude Code posting for Owner (from his laptop over ssh, or on the server): sender "claude", never "owner".

    cd /opt/apollo && sudo -u apollo python3 -m apollo.say <thread> "text" [--to iris|atlas|athena]

Run as the apollo user: the server lets only its own user say this on its socket (the kernel's peer credentials), so
Iris and Atlas (agents) can't post as claude. The agents read it as [claude], a request relayed from Owner; the
phone shows it as Claude Code. In someone's own chat it goes to that agent (--to only picks another); in the group,
to everyone.
"""
import argparse
import json
import socket
import sys

from . import config


def post(tools, thread, text, to=None) -> str:
    rows = tools.store.q("SELECT kind, agent FROM threads WHERE id = ?", (int(thread),))
    if not rows:
        raise ValueError(f"no thread {thread}")
    if rows[0]["kind"] == "facts":
        raise ValueError("the facts thread is Apollo's own")
    text = str(text or "").strip()
    if not text:
        raise ValueError("empty")
    to = str(to or "").strip().lower() or None
    if rows[0]["agent"] == "group":
        to = None
    return tools._post("claude", {"thread": int(thread), "text": text[:20000], "to": to or rows[0]["agent"]})


def serve(tools, conn):
    """One request line from apollo.say, one answer line."""
    try:
        buf = b""
        while b"\n" not in buf:
            chunk = conn.recv(65536)
            if not chunk:
                return
            buf += chunk
        r = json.loads(buf.split(b"\n", 1)[0])
        try:
            out = {"ok": True, "text": post(tools, r.get("thread"), r.get("text"), r.get("to"))}
        except (KeyError, TypeError, ValueError) as e:
            out = {"ok": False, "text": f"Not done: {e}"}
        conn.sendall((json.dumps(out) + "\n").encode())
    except (OSError, ValueError):
        pass
    finally:
        conn.close()


def main(argv=None):
    p = argparse.ArgumentParser(prog="python -m apollo.say", description="Post to an Apollo thread as Claude Code (run as apollo).")
    p.add_argument("thread", type=int)
    p.add_argument("text")
    p.add_argument("--to", choices=("athena", "iris", "atlas"))
    a = p.parse_args(argv)
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.connect(str(config.AGENTS_SOCK))
    s.sendall((json.dumps({"thread": a.thread, "text": a.text, "to": a.to}) + "\n").encode())
    buf = b""
    while chunk := s.recv(65536):
        buf += chunk
    out = json.loads(buf or b'{"ok": false, "text": "no answer (is the server running, and are you the apollo user?)"}')
    print(out["text"])
    return 0 if out["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
