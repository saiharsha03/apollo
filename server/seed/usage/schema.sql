-- One row per reading. The page shows the newest row per (who, meter); older rows are the history.
CREATE TABLE IF NOT EXISTS usage (
  id INTEGER PRIMARY KEY,
  at INTEGER NOT NULL,          -- ms of the reading
  who TEXT NOT NULL,            -- athena, iris, atlas
  meter TEXT NOT NULL,          -- managed_month, claude_5h, claude_7d, codex_5h, codex_week
  used_pct REAL,                -- 0-100, for windows
  used_usd REAL,                -- dollars, for managed_month
  cap_usd REAL,                 -- the cap (200 for managed_month)
  resets_at INTEGER,            -- ms when the window or month resets
  detail TEXT,                  -- "Opus 5.5", "brain = iris until Nov 1"
  source TEXT                   -- "Anthropic cost report", "own tally", "statusline"
);
CREATE INDEX IF NOT EXISTS usage_latest ON usage(who, meter, at);
