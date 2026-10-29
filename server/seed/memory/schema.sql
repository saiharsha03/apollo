-- One fact per row. confirmed = 1 when Owner said it himself or tapped "That's right". updated: ms since epoch.
CREATE TABLE IF NOT EXISTS facts (
  id INTEGER PRIMARY KEY,
  fact TEXT NOT NULL,
  topic TEXT NOT NULL DEFAULT 'general',
  source TEXT,
  confirmed INTEGER NOT NULL DEFAULT 0,
  updated INTEGER
);
CREATE INDEX IF NOT EXISTS facts_topic ON facts(topic);
