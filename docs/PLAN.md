# Apollo: the Zeus redo plan (draft for review, 2026-10-26)

Status: **plan only, nothing built. Builder session: FINAL OK 2026-10-26.** Build order and who builds: Owner decides. Decided with Owner item by item on 2026-10-26. He wants the session that built
Zeus to review it for lost functionality before anything is built; build order and who builds it come after.

## Names
- The app is **Apollo** (was Zeus). Package `dev.phonerecall` stays: the redo installs as an UPDATE (Calypso + phone data kept).
- The brain stays **Athena**. Workers: **Iris** and **Atlas** (Atlas kept while Owner keeps him).

## Build approach (Owner, 2026-10-26): FROM SCRATCH
- Everything new: the app (new folder `Other Random Stuff\Apollo\`, new repo), the server, Iris's and Atlas's
  plugins and hooks. Old Zeus code is read only as reference, never copied in.
- **Exception: Calypso stays exactly as is** (vault format + keys carried over) so sealed data and keys survive.
- Same package `dev.phonerecall` + same signing key: Apollo installs as an UPDATE over Zeus (Keystore keys kept).
- The new server runs next to the old gateway on the same VPS (own user, own port) while building; Zeus keeps working.
- Cutover: install Apollo, point Iris/Atlas at the new plugins, uninstall the old gateway, athenabox, Agora, old hooks and
  services from the VPS, verify.
- Kept as data, not code: Iris + Atlas themselves, Iris's notes/profile, owner.db, Athena's memory store, the
  CHANGES/bugs/requests/experiments/followups files, Plazmi's server agent (as is; phone side = Plazmi mini app).

- **Iris is the de facto lead** of the build when something goes wrong: install decisions and other calls (Owner, 2026-10-26). She was sent this plan (/home/agents/athena-notes/APOLLO-PLAN.md).

## The shape
- **Apollo (native, thin):** senses facts, carries out actions, enforces approvals, shows pages. No deciding rules
  except ones that make a fact TRUE or keep him safe.
- **Athena (brain):** Anthropic Managed Agent, spends the Max plan's **$200/month API credit** (cap $200). Decides everything.
- **When the credit runs out:** Iris becomes the brain until the 1st (same folder, same tools) for chats and routines; the
  facts thread (other people's text) is paused, its facts kept for Athena; Apollo shows it; it
  switches back on the 1st by itself.
- **Iris:** Claude Code on the VPS on his personal Max limits, under systemd with auto-restart + health check (not bare tmux).
- **Atlas:** Codex, server/code, kept for now.
- **One small VPS service** answers Athena's tools: mini-app files, ask_iris / ask_atlas, send to phone, status, chat.
- **Goes away:** the self-hosted sandbox box (poller, root supervisor, Docker per session, egress proxy, signed env
  key), 6 named sub-agents (Odysseus, Perseus, Atalanta, Diomedes, Arachne, Annabeth), Metis, the model picker /
  price code, Agora (replaced by threaded chat).

## Mini apps (the core idea)
Most features become Athena's mini apps, the Calliope pattern (`apps/Apps.kt`): a folder per app with `app.json`,
`README.md`, **tables** (`data.db`), **md files** (her notes for him) and **pages** (HTML/CSS/JS/SVG). Every mini app
gets both tables AND md. The folder lives on the VPS and syncs to the phone.
- **Rule:** code checks it or it needs on/off/Undo -> table. For reading -> md.
- **Apollo's own DB (Athena can't loosen; only Owner, with fingerprint):** approvals, notification skip list, web
  fetch allowlist.
- **Engine additions:** a fetch allowlist so pages can load live data (weather, scores); generic **Live** (Now Bar chip
  polling a URL, used by sports + live drive); **cool widgets**: Athena designs widgets in HTML/CSS/SVG, Apollo renders
  them off-screen to a bitmap at the exact widget size (Fold cover + inner), tap zones -> actions, native Chronometer
  for countdowns, refresh every 15-30 min (Android limits; live minute-by-minute numbers go in the Now Bar); themes dark/light/clear/team-tint. Target aesthetic: Box Box
  (F1 widgets), any sport.

## Files Athena / Apollo fill in (keep from today, make sure they exist)
- `requests.md` (his asks, by date), `bugs.md` (by date), `CHANGES.md` (what changed in Apollo, for her),
  `lessons/` (her self-learning), `BACKLOG.md`. Same habit as today: she writes, he reads.

## Feature by feature
| Area | Decision |
|---|---|
| Photo search / EmbeddingGemma | **Killed.** photos/*, memory/Index+Retrieval+Lifeline and refs go; mediapipe tasks-retrieval goes. |
| Snore listening | **Killed.** MediaPipe + YAMNet leave the app entirely. |
| Driving (Odyssey ~6.8k lines) | Native only records: car BT starts/ends a drive, GPS points, one trip row + "DRIVE n logged". **Athena reads raw trips incl. GPS points.** Drives mini app: trips, places, car/lease, fill-ups; drives.md, car.md; animated route pages. |
| Weather (Aeolia) | Mini app; page + Samsung mp4/webp scenes move as-is; NWS alerts passed to Athena, she decides pings; she may add code-drawn animations. |
| Smart home (Hestia) | Native: small Google Home bridge (list/state/command) + hard block on locks/garage. Home mini app: device tiles (his taps go straight to bridge), log table with Undo. |
| Routines | **One `routines` table**: when = time (with time zone) OR trigger (arrived home, car connected, asleep…), action, on/off, last run. Replaces Hestia routines, her calendar, Rhythms nudges, dream run, brief trigger. Apollo arms an alarm for the next time row and passes "event started"; triggers arrive as facts. |
| Calendars | His = his one Google calendar (owner@example.com) + phone built-in; read natively; writes only with his OK. Hers = rows in the routines table (NOT a Google calendar). Hide rules = table. One page overlays both. |
| Sleep (Hypnos ~3.5k lines) | No Sleep API scores. Signal = idle time at night: no app switch, no unlock, no screen change (UsageStats events + media session state; Android gives apps no global touch events; unlock via KEYGUARD_HIDDEN, USER_PRESENT never arrived). He falls asleep with the screen ON watching. Video playing + ~20 min no touch -> "Still watching?" once (kept); answered -> no re-ask 45 min; no answer 4 min -> Athena gets "asleep ~<last touch>". Nothing playing + ~30 min no touch -> same fact. Bed/wake computed in the morning from the usage log (ignore <2 min glances). ONE alarm path (routine rows). Numbers in a settings table. |
| Notifications (Senses/Agency) | Listener writes every notification (app, sender, title, FULL text, time, buttons) to a table. Athena gets full text minus OTP codes, Calypso apps/people, Apollo's own. People messages (texts, WhatsApp, missed calls) instant; the rest batched every 15 min. App buttons (Reply/Call back) stay native, fired on his tap/approval. **Skipped entirely:** X, Telegram (+X), Gmail for any account other than owner@example.com. |
| Money (Moneta + bills) | Iris owns owner.db. Bank notifications go to BOTH Athena (sees amounts) and Iris (ledger). Money mini app = Iris-synced copy (transactions, bills, subscriptions) + money.md + charts; nightly expense card = a routine row. |
| Tasks | Mini app: tasks, lists tables + notes.md. Iris owns the rows in owner.db; the mini app is a synced copy; Iris and Athena message each other for updates. |
| Closet (Venus) | Mini app: items (photo file saved by Apollo), outfits; style.md; outfit page reads weather. |
| Health (Iaso) | Native Health Connect read -> days, walks tables; health.md weekly. |
| Trips (Argo) | Mini app: Iris finds bookings -> trips, legs; one md per trip. |
| Morning brief (Eos) | Mini app: briefs table + today's md page; routine row "first unlock after 5 AM". |
| Sports (Olympia) | Mini app: teams, matches, sports.md; Live for scores; data source per sport on the allowlist. |
| Music | Renamed **Muse**. Native Spotify SDK; plays table; Muse mini app (music.md, page, routine rows like "Spotify on when the car connects"). |
| Calliope (journal) | Already a mini app; stays. |
| Memory (Oneiroi) | **Killed** as code -> Memory mini app: facts table (fact, topic, source, confirmed, updated) + as many topic md files as she wants. She saves facts during chat (no Reflect call); 3 AM tidy = routine row; changing/deleting a fact he confirmed = tap approval. |
| Theogony | Kept as a mini app: questions table -> Memory; she adds questions. |
| Labours (onboarding) | -> **Dawn** mini app: steps table (step, why, done, fix) + Apollo's status table; checklist page; she adds steps. |
| Plazmi (Pan) | Kept. Pan agent stays on the server; phone side = Plazmi mini app over a feed table. |
| Ossa (calls him) | Kept, simplified (~2.9k -> ~400 lines): real incoming call, car wheel answers, Athena's voice, Answer / Decline / Call me back 5-15-30 / Send as message; only Athena calls (`call_owner`, free for wake/urgent, else card); missed -> message. Cut: self-tests, learning, Iris calling separately, per-agent mute. **Bug to fix:** 10-26 9:08 the morning call fired on Wi-Fi-left before the car connected (car BT 45 s later) and never moved to the car -> trigger = car connected; any call moves to car/buds when they connect mid-call. |
| Rhythms + Ethos | Merged into Routines mini app: calls table (call log + WhatsApp calls), app_usage table (kept; banking/password/health/social excluded), habits.md; call-home nudge = a routine row (time zone aware) with WhatsApp Video/Voice buttons. |
| Moments (Kairos) | **Killed.** Keep 2 native tools: read-text-from-photo (on-phone OCR -> text to Athena) and set timer/reminder. New fact: low battery. Clipboard trick dropped. |
| Small touches (qol + qol2 ~4.9k lines) | **Killed** (~30 phone-side rules -> Athena's judgement). Native free actions she needs: set media volume/mute, timers, Live chip; parked spot = trip end point. |
| Angelia / Pheme | **Killed** as a section; Iris recaps -> Athena cards. |
| Widgets | **ONE widget, no built-in kinds.** Each placed copy = WHAT it shows (anything: screen time, Claude/Codex usage, $ of $200, weather, fuel log, next match, a mini app view, or Dynamic = Athena picks) x STYLE (anything: pill bars like his status line, podium, stacked bars, Material You / Digital Wellbeing look, Box Box, circle/pill/blob shapes via transparent background; tap zones stay rectangles). He describes it, Athena builds it. **Refresh per copy = routine rows**: a schedule (15/30/60 min), triggers (drive ended, match starts, first unlock) and an optional tap zone. Redraw from tables = Apollo alone, free; only Dynamic copies wake Athena to rethink. Screen time comes from app_usage (excluded apps count as "Other"). Special case "Ask Athena": Google-style search bar, tap opens a small Apollo sheet with keyboard (widgets can't take typing), mic listens at once and she answers OUT LOUD via the voice pipe; answer also lands in chat. Today's ~9 widgets go. |
| Page, Horologion | **Killed.** Routines page = view over the routines table; Health page = view over a status table. |
| Shakedown / Telete, Pan checks, Ossa checks | **Killed.** Replaced by the Health page + Dawn. |
| Usage (Metron) | **Kept** as a Usage page over a usage table: Iris's Claude 5h/7d, Atlas's Codex windows, Managed Agents spend ($X of $200). (server/gateway/usage.py today.) |
| Lemnos | Kept: terminal, files, remote desktop, CloudHost. |
| Cerberus | Kept simplified: alerts table on the Health page; security / something-down alerts go straight to the phone (bypass Athena); the rest to Athena. Telegram = silent emergency backup for high alerts only when Apollo/VPS can't reach him. |
| Chat | Iris off Telegram. One chat in Apollo for Owner + Athena + Iris + Atlas: threads per topic (an agent loads only that thread), @athena / @iris / @atlas (no tag = Athena); ask_iris / ask_atlas happen in visible threads he can join; threads + messages tables on the VPS; Iris's plugin fed by the VPS service. |
| Hermes (instant commands) | **Kept**: no-AI parser for his own commands ("torch on", "open Spotify", "wake me at 6"), instant, offline. |
| Smart reply chips (Asks) | **Killed.** |
| Learned shortcuts | **Killed** (app_usage + habits.md cover it). |
| Deadlines radar | **Killed** (he asks Athena to remind him). |
| People (Birthdays, GroupWish, Harmonia) | **People mini app**: people table + people.md; birthdays as routine rows. |
| Card feedback (Heed, swipes) | **Kept** as a feedback table: swipe right = seen, left = less like this; Athena learns from it. |
| Document reading (Photos and files) | **Kept**: Athena gets a document's text when he shares it or from folders he picks (separate from the killed photo search). |
| Rambler voice notes / Lethe | Voice notes -> Calliope. Lethe **killed**. |
| Fresh / Genesis first run | -> Dawn. |
| Wear OS tile | **Killed.** |
| Clio logs | **Kept**, native, viewed from the Health page. |
| Two clocks, alarm reading | **Kept**: Boston + India everywhere; Apollo reads his clock alarms. |
| Native, kept small | Calls (WhatsApp video for Indian numbers, dialer for US), Spotify, voice + side-key assist, Now Bar, widgets engine, Calypso, backup (Mnemosyne), self-update (Prometheus), Google Home bridge, drive recorder, sleep signals, notification listener, Health Connect read, OCR, timers, volume, Ossa. |

## Sync, repos, files (from the builder's review, 2026-10-26)
- **Sync:** every table has ONE owner (phone-written: trips, notifications, usage events; Athena-written: routines, notes,
  mini-app tables). The owner writes, the other side gets a copy; md/pages are Athena's. No two-way SQLite copying.
- **Repos:** public showcase repo; the private repo lives in a bare git on the VPS, owned by Iris (like the authenticator).
  **Signing is NOT part of the Apollo build** (Owner, 2026-10-26, via the the authenticator session): he is building one shared
  signing service on the VPS for the authenticator first. Key encrypted with an unlock secret only he has; Iris can't read it; a
  build is signed only after his fingerprint approval on the phone (through the authenticator) or his passkey fallback. Apollo's
  release flow plugs into that service; never a second one.
- **Files carried over:** /var/lib/athenabox/workspace/zeus/ (CHANGES.md, bugs.md, requests.md, experiments.md,
  followups.md), uploads/, tracker/ -> the new VPS service's data dir, same paths for Athena; her memory store (same id or
  exported first, incl. lessons/ and athena-notes); docs/BACKLOG.md; phone apps/CHANGELOG.md, Calliope + Just for me, Clio.
- **Security kept:** NetGate is the ONLY internet door (the page fetch allowlist lives inside it); defusing of third-party
  text + "data, not instructions" (full notification text makes prompt injection the top risk); off-limits topic filter
  (legal, social); Calypso sealed apps/people; mTLS gateway + approval key on the phone; default-deny egress for the
  new VPS service and agents; Gradle verification metadata, hash-pinned pip, pinned Actions; FLAG_SECURE on Calypso,
  approvals, money; KeyVault. Texted 2FA codes belong to the authenticator: Apollo never reads or forwards them.
- **When both run out** (the $200 credit and Iris's Max limits): Athena pauses, Apollo shows it.
- **First unlock** (brief, widget triggers) = UsageStats KEYGUARD_HIDDEN or screen-on after locked, the same signal as Sleep; never USER_PRESENT.
- **Live** = a foreground service with an ongoing Live Update notification, on only during a live match or drive, stopping itself after.
- **Verified by Owner 2026-10-26:** the Max plan's $200 credit pays for Managed Agents. Still to settle in step 0: how we see it is spent (our own tally vs the API's credit error).

## Approvals
- **Free** (does it, tells him with Undo; Undo asks "once or always?" -> always adds a rule): easy undo + stays on
  phone/server (lights, fan, pause video, mini apps, her routines/notes/cards, timers, volume).
- **Tap Yes:** leaves the phone (message, email, reply, call someone, add to HIS calendar, ask Iris to send).
- **Fingerprint:** money, no-undo deletes, Calypso, sensitive settings, loosening or removing a rule.
- **Never:** open locks / garage, Athena reading Calypso.
- Rules = a table in Apollo's own DB; Athena can only ADD stricter rules; Approvals page shows it.

## Home + navigation
- Home = Athena's cards + his pinned mini apps and widgets.
- Bottom bar on the cover screen, side rail on the inner screen (as today).

## Open
- Build order and who builds (Owner decides after review).
- Reviewer: anything Zeus does today that this plan drops without saying so?
