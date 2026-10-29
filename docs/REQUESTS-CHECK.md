# Athena's requests.md and bugs.md (Zeus) checked against Apollo (2026-10-26)

Source: /var/lib/athenabox/workspace/tracker/requests.md and bugs.md on the VPS (they carry over to Apollo's workspace
at cutover). ✅ covered by the design · ➕ gap, added to the build list · ⛔ gone with the feature (no longer applies).

## Covered
| Request | How Apollo covers it |
|---|---|
| Duplicate job results redelivered ~30× | One path, every message has an id and a delivered flag; no relay/outbox replay |
| Close tasks without a confirm | Task changes are easy-undo: free in the approvals table |
| Thursday drive-before-10 briefing call | `car` connect is an urgent fact; a routine row (trigger car:connected) per weekday |
| Trigger on drive start / leaving home | Same: car connect/disconnect facts, trips with GPS |
| Time-zone aware dates for India | Routines carry a tz; she sees full text and both clocks |
| Reminder at 8 AM IST | Routine rows with tz Asia/Kolkata |
| DST shift for the India call | Rows stored in IST, so Nov 1 moves the Boston time by itself |
| Family birthdays at their midnight | People app + routine rows in the person's zone |
| Refund / return tracker, spending watch | Iris owns owner.db; Money app synced copy she reads |
| Quiet-hours batching | Facts batch every 15 min; people messages instant; she decides what pings |
| Per-chat tone and language | Memory app (topic md files) |
| Duplicate cards from one message | No phone-side auto cards any more: she makes each card |
| Lodging gaps, lease input, match clash | Trips / Drives / Sports mini apps she builds |
| Iris ask.reply schema | Gone: plain thread_post in a visible thread |
| Atlas: replies keyed by id, recipient tags, tracing repeats | Threads, `to` tags, message ids, delivered flags |
| Answer to an event note not visible | Every Athena reply lands in a Chat thread he can read (facts thread included) |
| Read the raw notification text | Full text is in the facts |
| Memory can't hold names/amounts | Rule removed: her memory holds what she needs |
| Remember what he rejected | card_feedback facts + her memory |
| Weekly review pack | A routine row |
| Sports results digest, spoiler-safe | Sports app |
| False "left home" / false drives (bug) | Drives start only from the car's Bluetooth; no Wi-Fi/GPS guessing |

## Gone with the feature
Olympus empty / layout / panels / read_panels (bugs too), Parnassus duplicates, onboarding-contact tap, chat messages cut
off (chat sends only on Send), widget placeholder spec (new widget engine), set_wallpaper, Ethos/manage_habits.

## Gaps added to the build list
1. **Stays and places**: Kohl's arrival, saved places from repeated stops, "where now" (home/work/store/driving). The
   phone sends `stay` facts (≥5 min at one spot, with lat/lng, also on foot) and `place` arrive/leave facts.
2. **Room temperature / fan on a cold night**: the Google Home bridge sends device state facts (incl. temperature
   sensors and thermostats) and the overnight low comes from the weather app.
3. **One-shot routines**: a `once` flag on routine rows (time or trigger); the clock turns it off after it runs.
4. **Countdown cards**: `card` gets `until` (auto-removes) and an optional countdown target shown on the card.
5. **His calendar: edit and delete**, not only add: `calendar` facts carry event ids; `phone_do` gets
   `calendar_update` / `calendar_delete` (tap level).
6. **Withdraw a pending ask**: `phone_do` with `cancel` and the action id clears the waiting notification.
7. **Answer buttons in chat**: `thread_post` takes `choices`; the phone shows chips; his tap posts the choice.
8. **Focus / Do Not Disturb**: a `focus` fact when it changes (and when it ends).
9. **Resolved alerts list + server health**: Cerberus `alerts` table with resolve; a read-only `server_health` tool.
10. **One shared task store**: Iris owns the rows in owner.db; the Tasks app gets a two-way sync job (Iris's writes
    and Athena's both go through one tool, logged).
11. **OCR for scanned PDFs**: on the phone (shared files) and in Athena's sandbox (she can install tesseract there).
12. **Failed-call diagnostics (Ossa)**: sanitized error code, model, speaker, call id in Clio.
13. **Iris's mail read cap**: Iris's own mail tool (her setup, with Owner's OK), not Apollo.
