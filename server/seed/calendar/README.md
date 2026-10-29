# Calendar

For Athena. docs/PLAN.md, Calendars: his calendar is his one Google calendar plus the phone's own (read natively; writes
only with his OK, `phone_do calendar_add / calendar_update / calendar_delete`, tap). Yours is the routines table (time
rows in the routines app). This app is the one page that overlays both, and the rules for what it hides.

## Who owns what
- `events` is the phone's (fact kind `calendar`): read it, never write it. Event ids are what calendar_update and
  calendar_delete take. A repeating event has one row per occurrence (same id, its own start); editing the id changes
  the whole series.
- `hide_rules`, `event_picks`, `day_types`, `notes/calendar.md`, the page: yours. His taps on the page add his own
  hide rules (`by = 'owner'`) through the page; don't change or remove his rules without asking him.

## Hide rules (Zeus's Chronos, simplified)
- A rule matches when any of its words (separated by `|`) is in the event's title, case-insensitive, and its calendar
  is empty or the event's calendar.
- An event is hidden when a hide rule matches and no keep rule does. His rules beat yours. `event_picks` beats all
  rules for that one event.
- Hiding is only what the page shows: the brief, cards and your own reading still see every event (say "you hid this"
  when it matters, e.g. a clash with a hidden event).

## Day types
- One row per date: office, remote, off, travel, holiday. Fill it a week ahead from his calendar, drives (a drive to
  work = office) and what he says; mark the source. Routines and the brief read it.

## Birthdays
- The phone sends `birthday` facts from his contacts and Google's birthdays calendar into the people app's `birthdays`
  table (a phone table). Fold new ones into `people.birthday` and add the wish as a routine row in their time zone.

## The page
- Two weeks from today, a day at a time: his events (hidden ones behind "Show hidden"), your routine rows for that day
  marked as yours, and the day type. "Hide events like this" on an event adds his rule.
