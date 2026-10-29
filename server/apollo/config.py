"""Where Apollo's server keeps things. Everything can be moved with APOLLO_* environment variables (tests do)."""
import os
from pathlib import Path

DATA = Path(os.environ.get("APOLLO_DATA", "/var/lib/apollo"))
SECRETS = Path(os.environ.get("APOLLO_SECRETS", "/etc/apollo"))
RUN = Path(os.environ.get("APOLLO_RUN", "/run/apollo"))
# The signed release (deploy/apollo-release.sh): root-owned all the way down, so root never writes where apollo can
# plant a symlink; the server only reads it.
UPDATES = Path(os.environ.get("APOLLO_UPDATES", "/var/lib/apollo-updates"))

DB = DATA / "apollo.db"
APPS = DATA / "apps"
WORKSPACE = DATA / "workspace"          # CHANGES.md, bugs.md, requests.md, experiments.md, followups.md, uploads/
AGENTS_SOCK = RUN / "agents.sock"
# Copies of the files he attaches, where each agent's own file tools can open them (render puts the path in the message).
# Iris and Atlas (user agents, group apollo-agents): apollo:apollo-agents 2750, made by install.sh.
AGENT_FILES = Path(os.environ.get("APOLLO_AGENT_FILES", "/var/lib/apollo-files"))
# Athena: apollo:10021 2750 (box-install.sh), mounted read-only into her box at BOX_FILES_SEEN, inside her workdir.
BOX_FILES = Path(os.environ.get("APOLLO_BOX_FILES", "/var/lib/apollo-box/uploads"))
BOX_FILES_SEEN = "/workspace/uploads"

DEVICE_TOKEN = DATA / "device.token"      # the phone's bearer token (sha256 of it, hex), written by pairing
PAIR_CODE = DATA / "pair.code"            # a one-time pairing code root made (deploy/pair.sh), with its expiry
MCP_TOKEN = SECRETS / "mcp.token"         # Athena's bearer token for /mcp (sha256, hex); the token itself is in her vault
# Each session carries its own kind's token (its vault): mcp.token is the facts sessions' (other people's text, strict
# limits), this one the chat and routines sessions'. A call is trusted only with this token.
MCP_TOKEN_TRUSTED = SECRETS / "mcp-trusted.token"
ADMIN_KEY = DATA / "anthropic-admin.key"   # the admin key, for the Usage page (cost report); 0600
TOMTOM_KEY = DATA / "tomtom.key"           # Owner's TomTom key (Calypso slot tomtom), handed over with Athena's (0600)
NY511_KEY = DATA / "ny511.key"             # his 511NY key (Calypso slot ny511), handed over the same way (0600)
ANTHROPIC_KEY = DATA / "anthropic.key"     # Athena's API key, handed over once by the phone from Calypso (0600)
FCM_ACCOUNT = SECRETS / "fcm-service-account.json"
DEVICE_CA_KEY = SECRETS / "device-ca.key"  # signs the phone's client certificate at pairing (deploy/mtls.sh makes it)
DEVICE_CA = SECRETS / "device-ca.pem"      # the same CA, which Caddy trusts for client certificates
SARVAM_KEY = SECRETS / "sarvam.key"       # Sarvam (voice), root:apollo 0640, copied from Zeus's /etc/zeus/sarvam.key (install.sh)
BOX_STATUS = Path(os.environ.get("APOLLO_BOX_STATUS", "/var/lib/apollo-box/status"))    # root's timer: are her boxes up
BRAIN = SECRETS / "brain.json"            # agent id, environment id, vault id, memory store id

# Owner's numbers (docs/PLAN.md); he or Athena may change them in the settings table.
DEFAULTS = {
    "credit_cap_usd": 200.0,      # the Max plan's monthly API credit
    "facts_bundle_seconds": 3,    # facts landing this close together go in one turn (bundling, not a delay)
    # A thread's session is retired, with a handoff note to the next, so its context doesn't grow forever (Owner,
    # 2026-10-27): the facts thread's every 6 hours or 60 turns, a chat's once it holds more than 150 messages.
    "facts_roll_hours": 6,
    "facts_roll_turns": 60,
    "chat_roll_messages": 150,
    "run_stall_minutes": 5,       # a run with no progress this long is stopped and tried once in a fresh session
    "alert_grace_minutes": 10,    # a blinking status row (a socket, a fetch) alerts only when down this long (alerts.py)
    "alert_repop_hours": 2,       # a status row still down pops again after this long
    "heartbeat_minutes": 20,      # Athena's heartbeat, her time to act; it carries every fact that waited (Owner, 2026-10-27)
    # What wakes Athena at once (brain.wakes); everything else waits for the heartbeat. He or Athena may change these.
    "wake_kinds": ["call", "missed_call", "car", "alarm", "sleep", "trip", "nfc", "page_tap", "page_write", "page_tell", "card_feedback",
                   "action_result", "call_later", "reminder", "notify_unseen", "undo"],
    "wake_apps": ["com.whatsapp", "com.google.android.apps.messaging", "com.samsung.android.messaging", "org.telegram.messenger",
                  "org.thoughtcrime.securesms", "com.google.android.dialer", "com.samsung.android.dialer", "com.chase.sig.android",
                  "com.sofi.mobile", "com.ubercab", "com.google.android.calendar", "com.samsung.android.calendar"],
    "daily_usd": 6.6,
    "upload_max_mb": 20,          # a photo or file he attaches in chat
    "tomtom_daily_cap": 2500,     # TomTom's free tier per day; over it the tomtom tool says so
    "tiles_cache_mb": 300,        # street map tiles kept on disk (tiles.py); kept ones cost no TomTom call
    "ny511_daily_cap": 300,       # 511NY fetches a day (the state list is kept 5 minutes, so ~288 at most)
    "sarvam_daily_cap": 300,      # Sarvam voice calls a day (speech either way); over it the phone uses Android's own voice
    "sarvam_speaker": "ritu",     # Sarvam bulbul:v3 voice for Athena
    "group_relay_max": 20,        # agents' back-and-forth in the group without Owner before relaying pauses (protects the credit)             # the $200 credit spread over a month: a card when a day goes past it
}
