# People

Who matters to Owner (Birthdays, GroupWish and Harmonia from Zeus, now one app). Yours to run and reshape.

## Tables
- `people (id, name, relation, birthday, tz, phone, notes, last_contact)`
  - `relation`: what they are to him, in his words (mom, cousin, manager at the office).
  - `birthday`: `MM-DD`, or `YYYY-MM-DD` when you know the year (the page then shows the age).
  - `tz`: their time zone (`Asia/Kolkata` for family in India), so a wish lands at their midnight, not his.
  - `phone`: as he'd dial it (+91... for India; Apollo calls Indian numbers on WhatsApp video, US numbers on the dialer).
  - `notes`: short facts for this person (gift ideas, kids' names). Longer thinking goes in `notes/people.md`.
  - `last_contact`: ms since epoch. Keep it current from `call` facts and the `calls` table in the routines app, and
    from people messages in notification facts.

## Birthdays are routine rows
For each person with a birthday, add one row to the routines app's `routines` table:
`name` = "Birthday: <name>", `when_time` = "00:00", `tz` = their tz, `repeat` = `once:<next YYYY-MM-DD>`,
`action` = what to do (a card to wish them, with a WhatsApp button). The routines clock has no yearly repeat: when the
row fires, set its `repeat` to next year's date. A group wish (several people, one day) is one row whose action names them.

## birthday_add
Athena's tool for a birthday she noticed: fills an empty `birthday` (or adds the person), notes where it came from,
adds the routine row above and asks the phone to put it on his Google contact (`add_birthday`, his tap). Its Home card
`birthday:<id>` has Undo, which takes all of that back.

## His taps
- He adds a person or edits notes on the page: a `page_write` fact (INSERT into or UPDATE of `people`) reaches you.
  Add the birthday row for a new person.
- **Call** on a person's page is a `page_tap` fact `call` with `{id, name, phone}`: place the call through `phone_do`
  (it is his tap, but calling someone leaves the phone, so the approval rules still apply).

## Extending
Add columns (`ALTER TABLE people ADD COLUMN ...`), groups (a `groups` table and a join table), or pages. The widget
`widgets/birthdays.html` shows the next three birthdays.
