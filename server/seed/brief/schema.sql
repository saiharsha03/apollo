-- One brief per day. md: the brief as markdown. cards: a JSON list of {kicker, title, text, need}.
-- made / seen: ms since epoch (seen = when he first opened it, from the page).
CREATE TABLE IF NOT EXISTS briefs (
  date TEXT PRIMARY KEY,
  md TEXT,
  cards TEXT,
  made INTEGER,
  seen INTEGER
);
