-- Pan's feed (Zeus's PanFeed shape). Written only by the feed copy job on the VPS; read by the page. Untrusted text.
CREATE TABLE IF NOT EXISTS feed (
  id INTEGER PRIMARY KEY,
  ts INTEGER NOT NULL,          -- ms, when Pan wrote it
  kind TEXT NOT NULL,           -- diary, postcard, status, event, reply
  text TEXT NOT NULL,           -- plain text, control characters stripped, at most 500 characters
  place TEXT,                   -- where in Plazmi, at most 120 characters
  used INTEGER,                 -- status lines: tokens used today (UTC day)
  token_limit INTEGER,          -- status lines: the hard daily limit
  UNIQUE (ts, kind, text)
);
CREATE INDEX IF NOT EXISTS feed_ts ON feed(ts);
