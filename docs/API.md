# Apollo server contract (phone, Athena, Iris, Atlas)

One server on the VPS (`apollo-server`, its own user `apollo`, data in `/var/lib/apollo`, secrets in `/etc/apollo`).
Change this file and the code together.

## Who talks to it
- **The phone (Apollo):** HTTPS REST below, `Authorization: Bearer <device token>` (the token is sealed on the phone
  in Calypso's keys, slot `apollo_device`). Wake-ups are content-free FCM pushes `{"t":"sync"}`; content is always
  fetched with `GET /v1/sync`.
- **Athena (Managed Agent):** the MCP endpoint `POST /mcp` (streamable HTTP), `Authorization: Bearer <mcp token>`
  held in an Anthropic vault (substituted at egress; never in her sandbox). Two vaults: facts-thread sessions carry
  `mcp.token` (strict facts limits), chat and routines sessions carry `mcp-trusted.token`; the server decides a call's
  limits from its token alone.
- **Iris and Atlas:** the same tools through their channel plugin (stdio MCP) that talks to the server's unix socket
  `/run/apollo/agents.sock` (group `apollo-agents`). Thread messages for them arrive as channel events.
- **Claude Code (for Owner):** `cd /opt/apollo && sudo -u apollo python3 -m apollo.say <thread> "text" [--to iris|atlas|athena]`
  (stdlib only). The same socket, but only for apollo-server's own user (peer credentials); posts as sender `claude`.

Every message has one sender, set by the path it came in on, never by its text: `owner` (only `POST /v1/chat`, his
phone), `claude` (apollo.say), `apollo` (facts, his taps, action results, handoffs, stall lines, bank notifications),
`athena` / `iris` / `atlas` (their tools; a tool call can't be made as owner or claude). Athena reads each as
`[sender] text`, Iris and Atlas get it as the channel's `from`; the phone names them Owner, Athena, Iris, Atlas, Apollo
and Claude Code. A label-like `[owner` inside anyone's text but Apollo's reaches the agents as `(owner`.

## Ownership (sync)
- The mini-app folder lives on the server: `/var/lib/apollo/apps/<app>/` (app.json, README.md, data.db, pages/,
  notes/, widgets/). It syncs ONE WAY to the phone (manifest + changed files). The phone never writes it.
- Phone-owned data (trips, notifications, app usage, calls, health days, sleep signals...) reaches the folder only as
  **facts**: the phone posts facts, the server's ingester writes them into the owning app's table. Athena never
  writes a phone-owned table (`app.json` lists them under `phone_tables`).
- Apollo's own DB on the phone (approvals, skip list, fetch allowlist) is never synced and never written by agents.

## Phone REST

| Method | Path | Body / query | Answer |
|---|---|---|---|
| POST | `/v1/pair` | `{"code","token","csr"?}` (no bearer; the one-time code from deploy/pair.sh; csr = the phone's P-256 Keystore key) | `{"ok":true,"cert"?:PEM chain}` (cert only once deploy/mtls.sh made the device CA; then Caddy asks every /v1/* route except this one for it) |
| POST | `/v1/facts` | `{"facts":[{"id":"<a random id per fact>","kind":"notification","at":ms,"data":{...},"urgent":false}]}`; a fact whose id is already here is skipped (a resent batch), a malformed one is logged and skipped | `{"ok":true}` |
| GET | `/v1/sync` | `?since=<cursor>` | `{"cursor":n,"outbox":[{"id","kind","data"}],"apps":{"version":n}}` |
| GET | `/v1/activity` | | `{"athena":{"agent","tool","server","at"},...}` (latest per agent; polled every 3s while Apollo is on screen) |
| POST | `/v1/ack` | `{"ids":[...]}` | `{"ok":true}` |
| POST | `/v1/chat` | `{"thread":id or null,"title":str,"text":str,"to":"athena|iris|atlas|group","reply_to":id?,"upload":id?}` | `{"thread":id,"message":id}` |
| POST | `/v1/messages/{id}/react` | `{"emoji":str,"on":bool}` | `{"ok":true}` |
| POST | `/v1/messages/{id}/edit` | `{"text":str}` (his own only) | `{"ok":true}` |
| POST | `/v1/messages/{id}/delete` | (his own only; leaves a tombstone) | `{"ok":true}` |
| POST | `/v1/uploads` | `?name=`, raw bytes, Content-Type; cap `upload_max_mb` (20) | `{"upload":id}` |
| GET | `/v1/uploads/{id}` | | the bytes |
| POST | `/v1/tomtom_key` | `{"key":str}` (Calypso slot tomtom, sent with Athena's key) | `{"ok":true}` |
| POST | `/v1/ny511_key` | `{"key":str}` (Calypso slot ny511, sent with Athena's key) | `{"ok":true}` |
| POST | `/v1/spotify_tokens` | `{"access","refresh","expiresAt","scope"}` (Zeus's sealed sign-in, sent with the keys after his fingerprint; the server refreshes it from then on) | `{"ok":true}` |
| GET | `/v1/spotify/access` | | `{"access","expires_at"}`: a short-lived Spotify access token for the phone's player and playlist cards; the refresh token stays on the server (503 while there is no sign-in) |
| GET | `/v1/spotify/search?q=&type=&limit=`, `/v1/spotify/playlists`, `/v1/spotify/playlist_tracks?playlist=&offset=` | | `{"ok":true,"items":[...]}` or `{"ok":false,"detail"}`: Muse's page reading his Spotify (`apollo.spotify`), read only |
| POST | `/v1/clio` | a JSON list of Clio lines `{at,area,level,event,detail}` (Zeus's `{ts,...}` too); the phone's daily upload, sealed areas dropped, kept 30 days | `{"ok":true,"stored","dropped","pruned"}` |
| GET | `/v1/pan` | | `{"limits":{"daily_tokens","paused","updated","set"},"said":[...],"feed_ready":bool}` |
| POST | `/v1/pan/say` | `{"text"}` (his words to Pan) | `{"at","from","text"}` |
| PUT | `/v1/pan/limits` | `{"daily_tokens":int,"paused":bool}` (fingerprint on the phone) | the limits |
| POST | `/v1/pan/move_in` | `{"name","emoji"}` | `{"name","emoji","at"}` |
| POST | `/v1/pan/claim` | (fingerprint on the phone) | `{"link"}` (the plazmi.gg claim link) |
| POST | `/v1/pan/confirm` | `{"phrase":"three words"}` (fingerprint) | `{"ok":true}` |
| PUT | `/v1/pan/key` | `{"key":"sk-ant-..."}` (fingerprint; never echoed) | `{"ok":true,"restarted":bool}` |
| POST | `/v1/import/zeus-data` | Zeus's phone data as one JSON (server/apollo/zeusimport.py has the shape), on his tap, once; idempotent | `{"added":{...},"skipped_apps":[...],"sha","at"}` |
| GET | `/v1/import/zeus-data` | | the last import's answer, or `{}` |
| GET | `/v1/threads` | | `[{"id","title","updated","last"}]` |
| GET | `/v1/threads/{id}` | `?since=<message id>` (the ones after it), else the newest page or `?before=<message id>` (the page older than it); `&limit=` (200, at most 500); oldest first | `[{"id","sender","text","at","choices","reply_to","edited","deleted","file","reactions":[{"who","emoji"}]}]` |
| GET | `/v1/apps/manifest` | | `[{"app","path","sha256","size"}]` |
| GET | `/v1/apps/file` | `?app=&path=` | the bytes |
| GET | `/v1/tiles/{z}/{x}/{y}.png` | z 0-20, x and y inside 2^z (else 404) | a 256 px PNG of TomTom's dark street map (basic/night), fetched with the server's TomTom key (never sent to the phone) and kept on disk in `/var/lib/apollo/tiles` (setting `tiles_cache_mb`, oldest-used out first). A kept tile is free; a fetched one counts against `tomtom_daily_cap` (429 over it, 503 with no key or a TomTom failure). Pages get it as `/tile/{z}/{x}/{y}.png` (below) |
| GET | `/v1/apps/archived` | | `[{"name","title","icon","about"}]`: the archived apps (Athena's app_archive or his kill) |
| POST | `/v1/apps/{name}/archive` | (his one-tap kill) | `{"ok":true,"detail"}`; 404 `{"detail"}` when there is no such app. An `apps` outbox message follows |
| POST | `/v1/apps/{name}/restore` | (his tap) | `{"ok":true,"detail"}`; 404 `{"detail"}` when it isn't archived or the name is taken. An `apps` outbox message follows |
| POST | `/v1/actions/{id}` | `{"ok":bool,"detail":str}` | `{"ok":true}` (result of an outbox action) |
| GET | `/v1/status` | | `{"brain":"athena|iris|paused","usage":{...},"status":[...]}` |
| POST | `/v1/fcm` | `{"token":str}` | `{"ok":true}` |
| GET | `/v1/lemnos/status` | | `{"load","mem_used","mem_total","disk_used","disk_total","uptime","units":{unit:state},"control":[unit]}` |
| POST | `/v1/lemnos/unit` | `{"unit","action":"start\|stop\|restart"}` (only units in root's /etc/apollo/lemnos.json; phone asks fingerprint) | `{"unit","state"}` |
| GET | `/v1/lemnos/files` | `?root=workspace\|apps&path=` (no root: the roots) | `{"roots":[...]}` or `{"entries":[{"name","dir","size","modified"}]}` |
| GET | `/v1/lemnos/text` | `?root=&path=` | `{"text":str}` (UTF-8, at most 1 MB) |
| PUT | `/v1/lemnos/file` | `?root=&path=&replace=`, raw bytes (new file: 409 if taken; replace=true: an existing UTF-8 text file, 1 MB). Never data.db*, hidden names or a root | `{"ok":true}` |
| POST | `/v1/lemnos/rename` | `{"root","path","to"}` (`to` = new name in the same folder) | `{"ok":true}` |
| POST | `/v1/lemnos/folder` | `{"root","path"}` (a new empty folder; same names and roots as an upload; 409 if taken) | `{"ok":true}` |
| POST | `/v1/lemnos/delete` | `{"root","path"}` (no undo; phone asks fingerprint; 403 for a folder holding a data.db) | `{"ok":true}` |
| GET | `/v1/lemnos/sessions` | | `{"sessions":[{"name","attached","activity"}]}` (never Iris's claude-auto) |
| GET | `/v1/lemnos/challenge` | | `{"nonce","key","pending"}` (one-time, 60 s; `key` = sha256 hex of the active terminal key's DER, or null; `pending` = sha256 hex of a key waiting for root's approval, or null) |
| POST | `/v1/lemnos/key` | `{"key","nonce","sig"}` (base64url P-256 SubjectPublicKeyInfo DER; `sig` over `"<nonce>:register"` by that key) | `{"pending":code}` (16 hex, 64 bits; stored as pending, opens nothing until root approves it); 409 once another key is pending or active (root resets both) |
| POST | `/v1/lemnos/sessions` | `?nonce=&sig=` (signed `"<nonce>:new-shell"`) | `{"session":"shell-N","ticket"}` (a new bash in tmux, as agents; `ticket` opens its terminal once, 60 s); 403 without a good signature |
| DELETE | `/v1/lemnos/sessions/{name}` | only `shell-N`; `?nonce=&sig=` (signed `"<nonce>:kill:<name>"`) | `{"ok":true}` |
| WS | `/v1/lemnos/term` | `?session=&ro=&cols=&rows=` plus `&nonce=&sig=` (signed `"<nonce>:term:<session>"`) or a new shell's `&ticket=`, Bearer header. In: `{"t":"in","d"}`, `{"t":"resize","cols","rows"}`, `{"t":"scroll","dir":"up\|down\|end"}`. Out: `{"t":"history","d"}` once, then raw bytes | closes 4403 for claude-auto, a bad token or a missing/bad signature |
| GET | `/v1/lemnos/desktop` | | `{"state","ready","memory"}` (apollo-desktop.service) |
| POST | `/v1/lemnos/desktop` | `{"action":"start\|stop"}` | as GET |
| WS | `/v1/lemnos/desktop/view` | Bearer header; raw RFB bytes both ways | closes 4404 when the desktop isn't running |

**Lemnos terminal key.** The terminal and shell create/close need his fingerprint on the server's side, not just the
device token: the phone holds one Android Keystore P-256 key (alias `lemnos`, every use opened by fingerprint or PIN,
not invalidated by a new fingerprint enrolment) and signs `"<nonce>:<action>"` (ECDSA SHA-256, DER, base64url) over a
single-use challenge from `/v1/lemnos/challenge`. The first time, the phone registers the key (`/v1/lemnos/key`) as
pending and shows its code; root makes it active with `bash deploy/lemnos-approve.sh <CODE>`, so the device token alone
can never enrol a key. An active key is never replaced over the API. If the phone loses the key (reinstall, screen lock
removed), only the Terminal tab says so; root clears the server's key with `bash deploy/lemnos-reset.sh` (from the server
folder), the phone registers a new one ("Make a new key") and root approves its code. Files, Desktop and service
restarts are unchanged.
| GET | `/v1/backup` | | `{"backups":[{"id","size","created"}]}` (Mnemosyne; the 14 newest kept) |
| PUT | `/v1/backup/{id}` | raw `ABK1` file sealed on the phone | `{"ok":true}`; 409 if the id exists |
| GET | `/v1/backup/{id}` | | the sealed bytes |
| GET | `/v1/calypso/drops` | | `{"drops":[envelope + "origin"]}` (sealed by Iris or Atlas with apollo-calypso-drop; ciphertext only; origin from the file's owner: agents, apollo or unverified) |
| POST | `/v1/calypso/ack` | `{"ids":[...]}` | `{"ok":true,"removed":n}` (the server forgets them) |
| POST | `/v1/calypso/key` | `{"pem":str}` (Calypso's RSA public key) | `{"ok":true}`; agents read it at /run/apollo/calypso.pub.pem |
| GET | `/v1/backup/zeus` | | `{"backups":[{"id","size","created"}]}`: Zeus's old ZBK1 backups, read only (copied by deploy/zeus-backups.sh) |
| GET | `/v1/backup/zeus/{id}` | | the sealed bytes (opened on the phone with Zeus's 24-word phrase) |
| POST | `/v1/voice/tts` | `{"text","speaker"?}` (Sarvam bulbul:v3, at most 1500 characters) | WAV bytes; 503 no key, 429 over `sarvam_daily_cap`, 502 Sarvam failed (the phone then uses Android's voice) |
| POST | `/v1/voice/stt` | raw audio, its type in Content-Type (WAV is split into 25 s pieces; other audio at most 30 s; 40 MB) | `{"text":str}`; errors as tts |
| GET | `/v1/update` | | `{"version_code","version_name","sha256","size","notes"}` of the signed release deploy/apollo-release.sh left (signed by Owner's signing service after his fingerprint); `{}` while there is none |
| GET | `/v1/update/apk` | `?v=` | the signed APK (404 when `v` isn't the offered version) |

Outbox kinds: `card` (title, body, actions (each a label string, or `{"label","do"}` with do = call:<who> | alarm:<HH:MM> |
maps:<place> | open_app:<app> | open:<mini app> | thread:<id> (opens that chat if the phone has it; an unknown id does nothing), which the phone runs on his tap), app, from, optional until and countdown in epoch ms; the card's id is the
outbox item's id, except when data carries `id`: then it is a change to that card, same id, and the phone replaces its
title, body, actions, app, until and countdown but keeps its state, so a card he swiped away stays away),
`card_remove` (`{"id": "<card id>", "from"}`: the phone deletes that card from Home; an id it doesn't have is a no-op;
an older phone files it as an unknown kind and acks it), `notify` (title, body, priority; optional open (also thread:<id>), at in epoch ms, repeat + tz, check_after_min; or cancel: an earlier notify id), `action` (action, args, why),
`message` (thread, from, text, choices), `apps` (folder changed: re-read the manifest), `rule` (an approvals row Athena added:
stricter only), `call` (Ossa: Athena calls him, with the opening text; optional critical, after_minutes, unless_seen), `reaction` (thread, message, who, emoji, on),
`edit` (thread, message, text), `delete` (thread, message), `typing` (thread, who, on; the phone times it out after 3 min).
`message` also carries `reply_to`. `activity` (agent, tool, server, at: the tool, connector or event an agent is on right now, for the pixel characters; `tool` is `message`, `running` or `idle` for those events; never args or text; at most one per agent every 2s, idle always; the latest per agent is the `activity` setting).

Chat light events: his reactions to an agent's message, and his edits/deletes of a message an agent already read, reach
that agent without a run of their own: Athena sees them as lines on top of her next turn in that thread; Iris and Atlas
get a channel event (`"op":"event"`, kind reaction/edit/delete). Reactions never relay in the group. Agents read a message
with its quote (`[replying to Name: ...]`), the group's @mentions (`[for: iris]`) and attachments (`[attached: ...]`).

Activity sources: Athena's Managed Agents events (brain.py); Iris's and Atlas's Apollo tool calls over the socket; and
Iris's own Claude Code tools through `server/deploy/iris-activity-hook.sh` (PreToolUse + Stop, socket op `activity`).
The hook is NOT installed: installing it in Iris's Claude Code settings needs Owner's OK. Atlas has no stop
signal, so 2 minutes after his last tool call with nothing newer the server sends idle for him. An activity wakes the
phone (FCM, normal priority, collapse key `activity`) at most once per 30s, and not at all when the phone fetched in the
last 30s; while Apollo is open it polls `/v1/activity` instead.

Phone actions with their own feature: `live` (title, text, chip, url, text_path, chip_path, done_path, every_seconds,
until, countdown_to; the Now Bar chip, at most 6 h, a polled url must be on the fetch allowlist) and `live_stop`.

Fact kinds (phone -> server): `notification`, `trip` (start, end, miles, minutes, points [lat, lng, t, speed m/s], events [{kind, t, lat, lng, value}], max_mph, mounted; drives.trips with a score), `stay` (state here|left, start, end, lat, lng, place), `place`
(event arrive|leave, name, id), `sleep`, `unlock`, `app_usage`, `call`, `health_day`, `health_walk`, `battery`, `focus`
(on, mode), `alarm` (next_at, app), `music`, `car`, `calendar` (events with ids), `home` (devices incl. temperatures and
thermostats), `media`, `document` (text of a file he shared: name, mime, how, pages, text; from a folder he picked also via folder, folder, modified), `card_feedback`, `undo` (action, id: he undid a free action), `setup` (Dawn: key, ok, detail),
`birthday` (name, date MM-DD or YYYY-MM-DD, source contacts|calendar: the people app's `birthdays` phone table),
`home_pins` (pins: his Home pins as phone_setting home_pins takes them, after he pinned or unpinned one),
`place_pin` (name, lat, lng: his "Pin where I am"; saved in drives.places as kind pinned, the same name again moves it). `calendar` facts
also fill the calendar app's `events` phone table (the window from..to is replaced). More kinds:
`notify_unseen` (notify, title, minutes; urgent), `call_later` (call, minutes, by; or rung false when unless_seen held it),
`nfc` (tag: his name for it; urgent; routines match nfc:<name>), `tile` (slot, label: he tapped it), `reminder` (text, shown).
From mini-app pages and widgets (all urgent): `page_tap` (app, label, data; a page's `apollo.tap(label, data)`, or a
widget zone `data-tap="<label>"` with from widget; the home app's `apollo.tap("home", {id, on})` is carried out by the
phone first and also carries ok, detail), `page_tell` (app, text: a page's `zeus.tell`, or a Zeus-format widget button
with tell), `page_write` (app, sql, params: a page's own insert/update/replace on its app's non-phone tables, applied
by the server). `page_tap` and `page_write` carry gesture true only right after a touch on that page's view (1 s) or from a
Home widget button; only those go to "Your taps", the rest are facts. Widget zones: an element with `data-tap="open"` (the seed widgets' whole body) or no label opens the
app; `data-tap="zb:<n>"` is button n of a Zeus-format widgets/<w>.json; any other label is a `page_tap`.
Results of `phone_do` actions come back on `POST /v1/actions/{id}`, and the server adds each as an `action_result` fact (action, ok, detail) for Athena. ok false with detail "waiting for Owner (tap)" (or fingerprint) is not a failure: it waits for him, and his answer comes as a second `action_result` for the same action.
`call` (at, who, number, how phone|whatsapp|whatsapp video, dir incoming|outgoing|missed|connected..., missed, seconds), `media` (pkg, app,
title, series, state: something started playing, once per sitting; never music or Calypso apps), `charging` (state plugged|unplugged, level),
`charged` (level), `bluetooth` (state connected|disconnected, name, audio, car), `wifi` (state joined|left, ssid), `walk` (state started |
ended with start, end, minutes, to), `bank_sender` (sender, on: his tap confirming a bank's text sender). `health_day` also carries
weight_kg, food_kcal, protein_g, carbs_g, fat_g, hrv_ms, spo2_pct. Server-made: `weather_alert` (NWS, see server/seed/weather/README.md).

Bank notifications (a bank's app, or a text from a sender he confirmed) also go to Iris in her "Bank notifications" thread
(kind bank) through the socket. A confirmed bank's text with fraud words opens a high alert that goes straight to his phone.

## Street maps on pages
A page (or widget) shows a dark street map with the kit's map helper; nothing leaves the phone but to Apollo's server:
```html
<div id="map" style="height:240px"></div>
<script src="/kit/apollomap.js"></script>
<script>
apollomap.show("#map", {route: [[42.38, -71.07], [42.40, -71.05]],
  markers: [{lat: 42.38, lng: -71.07, color: "--alarm", label: "Hard brake"}], fit: true});
</script>
```
- `apollomap.show(el, opts)` returns a Promise of the Leaflet map. opts: `route` (`[[lat, lng], ...]`), `routes`
  (`[{points, color, weight}]`), `markers` (`[{lat, lng, color, label, size}]`, label = a small permanent tag), `fit`
  (default true), `center` + `zoom`, `height` (px, when the element has none; default 260), `still` (no pan or zoom, for
  small previews). Colors: any CSS color or a page variable name (`"--accent"`, the route's default; markers `"--ink"`).
- The phone serves `/kit/apollomap.js`, `/kit/leaflet.js` and `/kit/leaflet.css` (Leaflet 1.9.4, BSD-2-Clause,
  `app/src/main/assets/kit/leaflet-LICENSE.txt`) from its assets, and answers `/tile/{z}/{x}/{y}.png` (and Zeus's
  `/tile/dark/{z}/{x}/{y}` for zmap) from `GET /v1/tiles` with the device token, kept in memory and in the phone's cache
  (60 MB). Plain Leaflet works too: `L.tileLayer("/tile/{z}/{x}/{y}.png", {maxZoom: 18})`.

## Tools (Athena, Iris, Atlas: one list)
`apps_list`, `app_read`, `app_write`, `app_sql`, `app_create`, `app_archive`, `app_restore` (name; none lists the archived), `facts`,
`card` (returns its id; id + fields changes it, id + remove takes it off, list: the agent's cards still on Home, from the outbox
less removes, his seen / less-like-this swipes (card_feedback) and passed untils), `notify`,
`phone_do` (the full action list is in its tool description: incl. reminder, lock, wind_down, dnd, cancel_alarm, message, call_person by name, tile, shortcut, calendar_add / calendar_update / calendar_delete, add_birthday / remove_birthday (a real Google birthday on his contact), and cancel {id} to withdraw a waiting ask), `birthday_add` (people app birthday + routine row + add_birthday ask + a Home card `birthday:<id>` with Undo; allowed in facts turns), `call_owner`,
`add_rule`, `thread_post` / `thread_new` (optional choices; thread_post optional reply_to), `react` (message, emoji, remove), `upload_read`, `thread_read`, `ask_iris`, `ask_atlas`, `usage`, `server_health`,
`phone_setting` (nav_order takes places and `app:<name>` of an existing mini app, max 12, each once; the phone's bar shows 6 with Home and More, the rest under More; home_pins takes `app:<name>` tiles and `widget:<name>/<path>` live panels of widgets the app.json lists, max 12, each once, empty clears),
`alerts` (list, resolve), `tomtom` (route / nearby / reverse / search; Owner's key, capped by setting `tomtom_daily_cap`), `roads` (511NY events along a corridor or near a point; the state list is fetched with only the key, kept 5 min; cap `ny511_daily_cap`).
Restored from Zeus (server/apollo/restored.py): `spotify` (read only: search, playlists, playlist_tracks, top, recent, now, art;
only accounts.spotify.com and api.spotify.com), `talk_to_pan` (Athena only, for Owner), `search_chats` (only messages the agent can
see), `phone_logs` (Clio), `iris_db_schema` / `query_iris_db` (one SELECT on a filtered in-memory copy of owner.db), `drives_fix`
(stay_place, stay_delete, trip_edit, trip_delete, undo; kept in drives.fixes). `ask_iris` / `ask_atlas` refuse a new job while one
of that agent's Usage windows is at 90% or more; Metron alerts at 80% (normal) and 95% (high) once per window.

## Alerts (server/apollo/alerts.py)
Anything that breaks reaches Owner the same way: a `notify` with priority high (the phone shows it on its
"Athena: urgent" channel, importance high, so it pops up) and a `card` from apollo with id `alert:<key>`. One popup per
outage: a key that already popped stays quiet; a status row still down pops again after `alert_repop_hours` (2). When it
recovers, a `card_remove` for `alert:<key>`, a `notify` cancel of the popup and a low-priority "Fixed: <title>" go out.
Open alerts are the `alerts_live` setting, rows waiting out their grace `alerts_pending`. Text is short; key-like strings
are hidden.

| Key | Raised when | Cleared when |
|---|---|---|
| `status:<component>` | `set_status(component, False)`; `iris-*`, `atlas-*`, fcm, weather_now, nws, costs, athena, pan_feed, metron and box only once down for `alert_grace_minutes` (10) | the row goes ok |
| `stall:<thread>` | a run (a chat, or her heartbeat and facts turn) made no progress for `run_stall_minutes` (5), and again in a fresh session; or once, when a call that changes things (anything but a read: facts, app_read, a SELECT app_sql, her read/grep/glob/web tools...) has no result, so it may have run: then nothing replays on its own and the card has a Retry button (its card_feedback runs the thread once in a fresh session, the handoff listing what may have run); its popup and the card's Open go to `thread:<id>`; also when a retired session (a handoff out of time, or a stuck one) can't be confirmed stopped (idle or gone within 10 s, its handoff worker ended): no new session starts, his messages stay pending for the next try | she answers in that thread |
| `tool:<name>` | the same tool failed (network, disk: not bad input) 3 times in an hour | that tool's next success |
| `server:<route>` | an uncaught error in a request (the route pattern, never its ids) | that route's next answer under 500 |

Status rows that feed it: athena (run errors), brain (stalled; a month's hold says so itself and doesn't alert), heartbeat,
routines, facts, costs, fcm, nws, weather_now, pan_feed, metron, `<agent>-<channel>` connections, and `box`: Athena's two
boxes, from the file root's `apollo-box-status.timer` writes each minute (`/var/lib/apollo-box/status`, one
`<name> true|false|missing` line per box; stale after 5 minutes).

## Brain routing
- Default brain: Athena. Each chat thread is its own Managed Agent session (context stays small). Facts go to the
  `facts` thread: people messages at once, the rest every 15 min (numbers in the server's settings).
- The server tallies Managed Agents list cost per month from `session.usage`. At the cap ($200), or on a credit error
  from the API, the brain switches to Iris until the 1st; when Iris is out too, the brain is `paused` and the phone
  shows it.
