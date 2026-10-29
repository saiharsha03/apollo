-- birthday: 'MM-DD', or 'YYYY-MM-DD' when the year is known. tz: the person's time zone (wishes go at their midnight).
-- last_contact: ms since epoch of the last call or message between them.
CREATE TABLE IF NOT EXISTS people (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  relation TEXT,
  birthday TEXT,
  tz TEXT,
  phone TEXT,
  notes TEXT,
  last_contact INTEGER
);
