# Brief

Eos from Zeus: his morning brief, as an app. One short read that sets up the day.

## Table
- `briefs (date, md, cards, made, seen)`, one row per day (`date` = `YYYY-MM-DD`, Boston time).
  - `md`: the brief in markdown: a line or two up top, then short sections (`## Weather`, `## Today`, `## People`,
    `## Money`). His tone, no filler. Headings, lists, **bold**, *italic* and https links render.
  - `cards`: a JSON list shown above the text: `[{"kicker": "Leave by", "title": "8:10", "text": "Rain on the 290", "need": false}]`.
    `need: true` marks the one thing that needs him (shown in red). Keep it to three or four.
  - `made`: when you wrote it. `seen`: set by the page when he first opens today's brief.

## When
A routine row planted with this app fires at 05:00 Boston time: write today's row
(`INSERT OR REPLACE INTO briefs (date, md, cards, made) ...`). Show it at his **first unlock after 5 AM** (the first
`unlock` fact after 05:00): a card on Home pointing at this app. Mornings he's travelling, write it in his local time
(the trips app knows where he is).

Sources: the weather page's forecast (the closet page reads the same cache), his calendar facts and your routines
rows, People (birthdays today), Money (bills due), Trips, Memory.

## His taps
Opening today's brief writes `seen` (a `page_write` fact). Nothing to do; it tells you he read it.

## Extending
Add sections, a `notes/` md for what he liked or skipped (feedback facts), or a second widget.
`widgets/today.html` shows today's top card.
