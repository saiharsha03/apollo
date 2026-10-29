-- events is a phone table: the ingester keeps what the phone last read of his calendar (fact kind "calendar").
-- One row per occurrence: a repeating event's occurrences share its id and differ by start.
CREATE TABLE IF NOT EXISTS events (
  id INTEGER NOT NULL,          -- the event's id in the phone's calendar (what calendar_update / calendar_delete take)
  title TEXT,
  start INTEGER NOT NULL,       -- ms; all-day events at UTC midnight of their date
  end INTEGER,
  all_day INTEGER NOT NULL DEFAULT 0,
  location TEXT,
  calendar TEXT,                -- which of his calendars
  seen INTEGER,                 -- ms, when the phone last sent it
  PRIMARY KEY (id, start)
);

-- What the page hides (Zeus's Chronos rules). match: words separated by | (any of them, case-insensitive, in the title).
-- keep beats hide; his rules (by = 'owner') beat Athena's. calendar limits a rule to one calendar.
CREATE TABLE IF NOT EXISTS hide_rules (
  id INTEGER PRIMARY KEY,
  match TEXT NOT NULL,
  action TEXT NOT NULL DEFAULT 'hide' CHECK (action IN ('hide', 'keep')),
  calendar TEXT,
  label TEXT,                   -- what it's for, as he'd read it
  by TEXT NOT NULL DEFAULT 'athena',
  on_off INTEGER NOT NULL DEFAULT 1,
  at INTEGER                    -- ms
);

-- One event shown or hidden whatever the rules say (event id from events).
CREATE TABLE IF NOT EXISTS event_picks (
  event INTEGER PRIMARY KEY,
  show INTEGER NOT NULL,
  by TEXT NOT NULL DEFAULT 'athena'
);

-- The kind of each day (Zeus's day types): office, remote, off, travel, holiday. Athena fills it from his calendar,
-- his drives and what he tells her; routines can read it ("office days only").
CREATE TABLE IF NOT EXISTS day_types (
  date TEXT PRIMARY KEY,        -- YYYY-MM-DD, Boston time
  type TEXT NOT NULL,
  source TEXT,                  -- athena, owner, calendar
  note TEXT
);
