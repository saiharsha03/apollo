# Closet

Venus from Zeus: his clothes and what to wear today.

## Tables and files
- `items (id, photo, kind, name, color, warmth, notes, added, last_worn)`
  - `photo`: `photos/<file>.jpg`. Photos live only on his phone (Apollo keeps them in the app's phone folder when he adds
    a piece on the Everything tab, and Zeus's closet photos were copied there once); you never see or write them. An
    item without a photo is fine. His Add makes the row (a page_write) and taps `added` {kind, name}: fill in what's
    missing (warmth, notes) from what he named.
  - `kind`: `top`, `bottom`, `outer`, `shoes`, `accessory` (add kinds as you need).
  - `warmth`: 1 (summer) to 5 (deep winter), so an outfit adds up to the day.
  - `last_worn`: ms since epoch; keep it current when he wears an outfit.
- `outfits (id, date, items, weather, why, worn)`
  - `items`: a JSON list of item ids (`[3, 8, 12]`). `weather`: the day in words ("38°F, wind, rain by 4").
  - `why`: one line he'll see under it. `worn` = 1 when he taps **Wearing this**.
- `notes/style.md`: his style in your words: what he likes, what he never wears, colours that go together.

## How it runs
- Suggest an outfit for a day: `INSERT INTO outfits (date, items, weather, why) ...`. The page shows the newest row for
  today, with the live weather from the weather page's cache when it's there (same phone, same storage).
- Skip what he wore in the last few days (`last_worn`), and follow `style.md`.
- His taps: **Wearing this** is a `page_write` fact (`UPDATE outfits SET worn = 1`): update `last_worn` on its items.
  **Something else** is a `page_tap` fact `other` with `{outfit}`: suggest another one.
- New clothes: he sends photos (or tells you); add rows to `items`.

## Extending
Laundry, packing lists for a trip (the trips app), a widget with today's outfit.
