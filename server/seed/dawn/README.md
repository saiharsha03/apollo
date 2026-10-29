# Dawn

Apollo's first run and setup checklist (Zeus's Labours, Fresh and Genesis, now one app). He opens it to see what's left.

## Tables
- `steps (id, key, step, why, fix, done, sort)`: the checklist. Yours: add, reword, reorder (`sort`), remove.
  - `key`: ties a step to Apollo's `status` row. Keep the keys below as they are; give new steps a new key.
  - `fix`: exactly where to tap, in Apollo's own words (More > Senses > Usage). Keep it current when screens move.
  - `done`: 1 when he taps **I did this** on the page (a `page_write` fact) or when you know it's done (Iris's plugins).
    Steps Apollo can ask for itself have **Allow** instead and tick from their status row, never `done`.
- `status (key, ok, detail, at)`: a **phone table**: only Apollo writes it, from `setup` facts `{key, ok, detail}`.
  `ok` = 1 means it is in place right now. If a status goes from ok to not ok (a permission revoked), tell him.

A step counts as done when `done = 1` or its status row has `ok = 1`. The page shows both.

## Keys Apollo reports (as the phone grows them)
`pair`, `brain_key`, `notifications`, `usage`, `location`, `background_location`, `car`, `health`, `health_write`,
`calendar`, `call_log`, `activity`, `battery`, `google_home`, `spotify`, `widget`, `zeus_import`, `docs_folders`.
Also one row per permission Apollo asks for (`perm_read_call_log`, `perm_record_audio`...) and one per Calypso key slot
(`key_anthropic`, `key_tomtom`...: present or empty, never the key). Apollo sends a row only when it changes.
`box_key` and `iris` live on the server: they are yours to mark.

## His taps
- **Allow** (a step the phone can ask for itself): Apollo shows Android's real prompt or opens the exact settings screen,
  then re-reports the status row at once, so the step ticks itself. The keys: `location`, `background_location`,
  `calendar`, `call_log`, `activity`, any `perm_*` row (`perm_record_audio`; Health Connect ones like
  `perm_read_resting_heart_rate` open Health Connect's sheet), `health`, `health_write`, `notifications`, `usage`,
  `battery`, `docs_folders`, `car` (a list of his paired devices), `google_home`, `widget`. A new step with one of these
  keys gets Allow too; nothing else can (it's a fixed list on the phone). No fact for the tap: the status row is the answer.
  If it stays not ok after Allow, he said no or Android didn't show the prompt (asked twice and refused): offer Help.
- **I did this** (everything else: `pair`, `brain_key`, `box_key`, `iris`, `spotify`...): `UPDATE steps SET done = 1`. Check it if you can (a status row, a fact), and say so if it isn't.
- **Help**: a `page_tap` fact `help` with `{key, step}`. Walk him through it in chat.

## Extending
Add steps for new features (`INSERT INTO steps (key, step, why, fix, sort) ...`). `widgets/setup.html` shows
"n of m done" until everything is.
