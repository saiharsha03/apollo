"""Iris and Atlas: their channel plugins connect to a unix socket (group apollo-agents only) and speak JSON lines.

  plugin -> server   {"op":"hello","agent":"iris","channel":"dm"|"group"} · {"op":"tool","id":n,"name":...,"args":{...}}
  hook -> server     {"op":"activity","agent":"iris","tool":"Bash"|"mcp__gmail__search"|"idle"} (no hello; one line, then closes)
  server -> plugin   {"op":"message","thread":t,"id":m,"from":...,"text":...,"brain":bool} · {"op":"result","id":n,"text":...,"content":[...]}
                     {"op":"event","kind":"reaction"|"edit"|"delete","thread":t,"text":...}

Iris and Atlas run as one Unix user, so the socket can't tell them apart beyond what hello says (as with Zeus).
Messages for an agent that isn't connected wait (no deliveries row for it) and go out when it connects.
"""
import json
import os
import socket
import threading

from . import config
from .store import HUMAN

AGENTS = ("iris", "atlas")
AGENT_USERS = {"agents"}   # the Unix user Iris and Atlas run as
# Atlas has no hook that says he stopped: this long after his last tool call with nothing newer, he is idle.
IDLE_AFTER = {"atlas": 120.0}


def split_tool(name: str) -> tuple:
    """Claude Code names a connector's tool mcp__<server>__<tool>: (tool, server). Its own tools: (name, "")."""
    if name.startswith("mcp__") and name.count("__") >= 2:
        _, server, tool = name.split("__", 2)
        return tool, server
    return name, ""


class Bus:
    def __init__(self, store):
        self.store = store
        self.brain = None          # set by main: Brain.enqueue for athena
        self.tools = None          # set by main: Tools
        # (agent, channel) -> connection. Iris runs two plugins (Owner, 2026-10-26): "dm" carries only his own chat with
        # her; "group" carries the group chat and jobs from Athena or Atlas.
        self.conns: dict[tuple, socket.socket] = {}
        self.lock = threading.Lock()
        self._idle: dict[str, threading.Timer] = {}

    def deliver(self, agent: str, thread: int, mid: int, as_brain: bool = False):
        if agent == "athena":
            self.brain.enqueue(thread)
        elif agent in AGENTS:
            group = self.store.thread_agent(thread) == "group"
            if agent == "iris" and not as_brain and group and self.store.held_brain() == "iris":
                return      # Athena's copy reaches her as the brain (brain.Brain._to_iris), with brain: true
            if as_brain and not group:      # a group message stays everyone's (Atlas's undelivered goes by to_agent)
                self.store.x("UPDATE messages SET to_agent = 'iris' WHERE id = ?", (mid,))
            self._push(agent, mid, as_brain)

    def group(self, thread: int, mid: int, sender: str):
        """An agent spoke in the group chat: the others hear it, until they've gone back and forth too long without Owner."""
        if self.store.agent_run(thread) > int(self.store.setting("group_relay_max")):
            self.store.send("notify", {"title": "The group chat paused", "body": "Athena, Iris and Atlas went back and forth for a while without you. "
                                       "Write in the group to let them carry on.", "priority": "normal", "from": "apollo"})
            return
        for agent in ("athena", "iris", "atlas"):
            if agent != sender:
                self.deliver(agent, thread, mid)

    def channel(self, agent: str, m: dict) -> str:
        """dm when Owner (or Claude Code for him) writes in that agent's own chat; everything else (the group chat, agents'
        jobs) is group."""
        return "dm" if m["sender"] in HUMAN and self.store.thread_agent(m["thread"]) == agent else "group"

    def _push(self, agent: str, mid: int, as_brain: bool = False):
        if self.store.q("SELECT 1 FROM deliveries WHERE message = ? AND agent = ?", (mid, agent)):
            return      # a group message reaches Iris-as-brain twice (as herself and for Athena): once is enough
        m = self.store.message(mid)
        with self.lock:
            # A plugin that hasn't said its channel (older ones) takes both.
            conn = self.conns.get((agent, self.channel(agent, m))) or self.conns.get((agent, "any"))
        if not conn:
            return
        try:
            group = self.store.thread_agent(m["thread"]) == "group"
            self._send(conn, {"op": "message", "thread": m["thread"], "id": m["id"], "from": m["sender"], "text": self.store.render(m, agent), "brain": as_brain,
                              "room": "group" if group else "dm"})
            self.store.delivered(mid, agent)
            if m["sender"] in HUMAN and not group:
                self.store.send("typing", {"thread": m["thread"], "who": agent, "on": True})   # until her answer lands (the phone times it out)
        except OSError:
            with self.lock:     # keys are (agent, channel): drop whichever this dead socket was under
                for k in [k for k, v in self.conns.items() if v is conn]:
                    self.conns.pop(k)

    def event(self, agent: str, thread: int, kind: str, text: str):
        """A light event (his reaction, edit or delete) for Iris or Atlas: sent if the plugin is there, otherwise dropped."""
        with self.lock:
            chan = "dm" if self.store.thread_agent(thread) == agent else "group"
            conn = self.conns.get((agent, chan)) or self.conns.get((agent, "any"))
        if conn:
            try:
                self._send(conn, {"op": "event", "kind": kind, "thread": thread, "text": text, "room": chan})
            except OSError:
                pass

    @staticmethod
    def _send(conn, obj):
        conn.sendall((json.dumps(obj) + "\n").encode())

    def activity(self, agent: str, tool: str, server: str = ""):
        """An agent's tool to the phone; for an agent in IDLE_AFTER, idle follows when nothing newer comes."""
        self.store.activity(agent, tool, server)
        after = IDLE_AFTER.get(agent)
        if after is None:
            return
        with self.lock:
            old = self._idle.pop(agent, None)
            if old:
                old.cancel()
            if tool != "idle":
                t = threading.Timer(after, self.store.activity, (agent, "idle"))
                t.daemon = True
                self._idle[agent] = t
                t.start()

    def connected(self) -> list:
        with self.lock:
            return sorted(f"{a}:{c}" for a, c in self.conns)

    # ---- the socket ----
    def serve(self):
        path = config.AGENTS_SOCK
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            path.unlink()
        srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        srv.bind(str(path))
        # Anyone may connect; _client lets in only the users in AGENT_USERS (the kernel's peer credentials, which a
        # process can't fake), so Iris's long-running tmux needs no new group.
        os.chmod(path, 0o666)
        srv.listen(8)
        threading.Thread(target=self._accept, args=(srv,), daemon=True, name="agents").start()

    def _accept(self, srv):
        while True:
            conn, _ = srv.accept()
            threading.Thread(target=self._client, args=(conn,), daemon=True).start()

    def _peer(self, conn) -> str:
        """"agent" for the users in AGENT_USERS, "self" for apollo-server's own user (apollo.say), else ""."""
        try:
            import pwd
            import struct
            _, uid, _ = struct.unpack("3i", conn.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i")))
            if uid == os.getuid():
                return "self"
            return "agent" if pwd.getpwuid(uid).pw_name in AGENT_USERS else ""
        except (OSError, KeyError, ImportError, AttributeError):
            return ""

    def _client(self, conn):
        peer = self._peer(conn)
        if peer == "self":      # Claude Code's posts for Owner (python -m apollo.say, run as apollo): one line, one answer
            from . import say
            return say.serve(self.tools, conn)
        if peer != "agent":
            conn.close()
            return
        agent, chan = None, None
        buf = b""
        try:
            while True:
                chunk = conn.recv(65536)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    msg = json.loads(line)
                    if msg.get("op") == "hello" and msg.get("agent") in AGENTS:
                        agent = msg["agent"]
                        chan = msg.get("channel") if msg.get("channel") in ("dm", "group") else "any"
                        with self.lock:
                            self.conns[(agent, chan)] = conn
                        self.store.set_status(f"{agent}-{chan}", True, "connected")
                        for m in self.store.undelivered(agent):
                            self._push(agent, m["id"], agent == "iris" and self.store.held_brain() == "iris")
                    elif msg.get("op") == "tool" and agent:
                        self.activity(agent, msg.get("name", ""), "apollo")
                        # The group plugin carries jobs and the group, not his own chat with her: what it changes
                        # is on her own (a Home card with Undo, changes.py).
                        content = self.tools.content(agent, msg.get("name", ""), msg.get("args") or {}, own=f"{agent}:group" if chan == "group" else None)
                        text = "\n".join(c.get("text", "") for c in content)
                        self._send(conn, {"op": "result", "id": msg.get("id"), "text": text, "content": content})
                    elif msg.get("op") == "activity" and msg.get("agent") in AGENTS:
                        # deploy/iris-activity-hook.sh: Claude Code's own tools (Bash, Read...), and idle when she stops.
                        self.activity(msg["agent"], *split_tool(str(msg.get("tool", ""))))
        except (OSError, ValueError):
            pass
        finally:
            if agent:
                with self.lock:
                    for k, v in list(self.conns.items()):
                        if v is conn:
                            self.conns.pop(k)
                            self.store.set_status(f"{k[0]}-{k[1]}", False, "disconnected")
            conn.close()
