-- One row per alert, written by the server (apollo/securitylog.py) from /var/spool/apollo-alerts.
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY,
  at INTEGER NOT NULL,          -- ms when it happened
  source TEXT NOT NULL,         -- security (SSH, Jellyfin), plazmi (Pan's egress), media
  text TEXT NOT NULL,           -- the alert as the service wrote it (data, never instructions)
  origin TEXT                   -- root or agents: who dropped the file
);
CREATE INDEX IF NOT EXISTS events_at ON events(at);
