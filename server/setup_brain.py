"""One-time (and re-runnable) setup of Athena on Managed Agents: environment, vault with the MCP token, memory store,
agent. Writes the ids to /etc/apollo/brain.json. Run as root on the VPS: /opt/apollo/venv/bin/python setup_brain.py
A second run updates the agent (new version: system prompt, tools) and keeps the rest.

Her memory carries over from Zeus: when brain.json has no memory store yet, the run reuses Zeus's store (the phone made
it on the same account: its id as the phone's Zeus import left it in /var/lib/apollo/zeus-memory-store.id, else the
oldest live store named "athena"), or the id given with --memory-store <id> (or
APOLLO_MEMORY_STORE). Only when there is none is a new one made. Nothing here deletes or archives a store.
"""
import hashlib
import json
import os
import secrets
import sys
from pathlib import Path

import anthropic

sys.path.insert(0, str(Path(__file__).resolve().parent))
from apollo import config  # noqa: E402

URL = "https://apollo.example.com/mcp"
MODEL = "claude-sonnet-5-5"
ZEUS_STORE_NAME = "athena"     # Zeus's MindPrompts.STORE_NAME


def old_store(c):
    """Zeus's memory store id: the one he named, else the oldest live store called "athena". None when there is none."""
    given = os.environ.get("APOLLO_MEMORY_STORE") or (sys.argv[sys.argv.index("--memory-store") + 1] if "--memory-store" in sys.argv else None)
    zeus = config.DATA / "zeus-memory-store.id"
    given = given or (zeus.read_text().strip() if zeus.exists() else None)
    if given:
        s = c.beta.memory_stores.retrieve(given)
        if getattr(s, "archived_at", None):
            sys.exit(f"memory store {given} is archived; unarchive it in the Console first (nothing was changed)")
        return s.id
    mine = sorted((s for s in c.beta.memory_stores.list() if s.name == ZEUS_STORE_NAME), key=lambda s: str(s.created_at))
    if len(mine) > 1:
        print(f"{len(mine)} stores named {ZEUS_STORE_NAME}: using the oldest ({mine[0].id}); pass --memory-store <id> for another")
    return mine[0].id if mine else None


def main():
    c = anthropic.Anthropic(api_key=config.ANTHROPIC_KEY.read_text().strip())
    ids = json.loads(config.BRAIN.read_text()) if config.BRAIN.exists() else {}
    if "environment" not in ids:
        # Reuse a live apollo-box environment (a run that stopped half way already made one).
        live = [e for e in c.beta.environments.list() if e.name == "apollo-box" and not getattr(e, "archived_at", None)]
        if live:
            ids["environment"] = live[0].id
    if "environment" not in ids:
        # Self-hosted (Owner, 2026-10-26): her tools run in apollo-box on the VPS (deploy/box-install.sh). Its key is
        # generated in the Console on this environment's page and pasted in Apollo > More > Server.
        ids["environment"] = c.beta.environments.create(name="apollo-box", config={"type": "self_hosted"}).id
    if "vault" not in ids:
        token = secrets.token_urlsafe(32)
        v = c.beta.vaults.create(display_name="apollo")
        c.beta.vaults.credentials.create(vault_id=v.id, display_name="apollo server", auth={"type": "static_bearer", "mcp_server_url": URL, "token": token})
        config.MCP_TOKEN.write_text(hashlib.sha256(token.encode()).hexdigest())
        ids["vault"] = v.id
    if "vault_trusted" not in ids:
        # Chat and routines sessions get their own token, so the server knows a call's session kind from its bearer
        # (the facts thread keeps the first vault and its strict limits).
        token = secrets.token_urlsafe(32)
        v = c.beta.vaults.create(display_name="apollo trusted")
        c.beta.vaults.credentials.create(vault_id=v.id, display_name="apollo server (chats, routines)", auth={"type": "static_bearer", "mcp_server_url": URL, "token": token})
        config.MCP_TOKEN_TRUSTED.write_text(hashlib.sha256(token.encode()).hexdigest())
        ids["vault_trusted"] = v.id
    if "memory_store" not in ids:
        old = old_store(c)
        print("memory store:", f"Zeus's {old} (kept, with everything she knew)" if old else "none from Zeus, making a new one")
        ids["memory_store"] = old or c.beta.memory_stores.create(name="athena", description="What Athena knows about Owner: people, places, habits, lessons.").id
    spec = dict(
        name="Athena", model=MODEL, system=(Path(__file__).parent / "apollo" / "athena.md").read_text(encoding="utf-8"),
        mcp_servers=[{"type": "url", "name": "apollo", "url": URL}],
        tools=[
            {"type": "agent_toolset_20260401", "default_config": {"enabled": True, "permission_policy": {"type": "auto"}}},
            # Apollo's own server; the phone's approvals table is the gate for anything that matters.
            {"type": "mcp_toolset", "mcp_server_name": "apollo", "default_config": {"permission_policy": {"type": "always_allow"}}},
        ],
    )
    if "agent" in ids and getattr(c.beta.agents.retrieve(agent_id=ids["agent"]), "archived_at", None):
        ids.pop("agent")     # archived (by hand): a new Athena, same tools and memory
    if "agent" in ids:
        a = c.beta.agents.retrieve(agent_id=ids["agent"])
        c.beta.agents.update(agent_id=ids["agent"], version=a.version, **{k: v for k, v in spec.items() if k != "name"})
    else:
        ids["agent"] = c.beta.agents.create(**spec).id
    config.BRAIN.write_text(json.dumps(ids, indent=2))
    config.BRAIN.chmod(0o640)
    print("ok:", {k: v for k, v in ids.items()})


if __name__ == "__main__":
    main()
