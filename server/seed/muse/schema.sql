-- plays is a phone table: only the facts ingester writes it (fact kind "music"), Athena reads it.
CREATE TABLE IF NOT EXISTS plays (
  id INTEGER PRIMARY KEY,
  at INTEGER NOT NULL,          -- ms, when the track started
  track TEXT,
  artist TEXT,
  album TEXT,
  uri TEXT,                     -- spotify:track:...
  seconds INTEGER,              -- how long he listened (NULL while playing)
  source TEXT                   -- spotify, or the media app the phone saw
);
CREATE INDEX IF NOT EXISTS plays_at ON plays(at);
