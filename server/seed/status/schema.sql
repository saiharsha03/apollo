-- Cerberus's alerts, and the server's parts as last checked.
CREATE TABLE IF NOT EXISTS alerts (
  id INTEGER PRIMARY KEY,
  at INTEGER NOT NULL,          -- ms
  level TEXT NOT NULL,          -- high, warn, info
  source TEXT NOT NULL,         -- cerberus, server, brain, iris, atlas, phone, pan...
  text TEXT NOT NULL,
  resolved_at INTEGER,          -- ms; NULL while open
  resolved_by TEXT,             -- owner (his tap), athena, iris, atlas, auto
  note TEXT                     -- what fixed it
);
CREATE INDEX IF NOT EXISTS alerts_open ON alerts(resolved_at, at);

-- One row per part of the server (the same names as the server's status table: server, heartbeat, routines, brain...).
CREATE TABLE IF NOT EXISTS server_health (
  component TEXT PRIMARY KEY,
  ok INTEGER NOT NULL,
  detail TEXT,
  checked INTEGER               -- ms
);

CREATE VIEW IF NOT EXISTS open_alerts AS
  SELECT * FROM alerts WHERE resolved_at IS NULL
  ORDER BY CASE level WHEN 'high' THEN 0 WHEN 'warn' THEN 1 ELSE 2 END, at DESC;
