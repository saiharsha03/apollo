-- trips: one per trip. slug names its md file (notes/trips/<slug>.md). start / end: YYYY-MM-DD.
-- status: planning, booked, on, done, cancelled.
CREATE TABLE IF NOT EXISTS trips (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  slug TEXT UNIQUE,
  place TEXT,
  start TEXT,
  end TEXT,
  status TEXT NOT NULL DEFAULT 'planning',
  updated INTEGER
);
-- legs: each booking or step. kind: flight, train, bus, car, stay, event. start_at / end_at: local ISO time
-- (2026-12-19T07:05) with tz, the zone that time is in. ref: confirmation code. detail: seat, terminal, address.
CREATE TABLE IF NOT EXISTS legs (
  id INTEGER PRIMARY KEY,
  trip INTEGER NOT NULL,
  kind TEXT NOT NULL,
  title TEXT NOT NULL,
  start_at TEXT,
  end_at TEXT,
  tz TEXT,
  from_place TEXT,
  to_place TEXT,
  ref TEXT,
  detail TEXT,
  source TEXT
);
CREATE INDEX IF NOT EXISTS legs_trip ON legs(trip, start_at);
