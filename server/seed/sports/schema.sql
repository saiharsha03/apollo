-- Olympia. Athena owns every row: she fills them from the score sources on the fetch allowlist (see README).
CREATE TABLE IF NOT EXISTS teams (
  id INTEGER PRIMARY KEY,
  sport TEXT NOT NULL,          -- f1, cricket, football, nfl, nhl, nba...
  league TEXT,                  -- "Formula 1", "IPL", "Premier League", "NFL"
  name TEXT NOT NULL,           -- "Ferrari", "India", "Boston Bills"; a driver is a team of one
  short TEXT,                   -- "FER", "IND", "BUF"
  color TEXT,                   -- the team's own colour as #RRGGBB (the page's only colour besides ink and red)
  following INTEGER NOT NULL DEFAULT 1,
  source_id TEXT,               -- the id at the score source (ESPN team id, Jolpica constructorId...)
  UNIQUE (sport, name)
);

CREATE TABLE IF NOT EXISTS matches (
  id INTEGER PRIMARY KEY,
  sport TEXT NOT NULL,
  league TEXT,
  title TEXT,                   -- "Mexico City Grand Prix", "2nd ODI"; for races there are no home/away
  home_id INTEGER REFERENCES teams(id),
  away_id INTEGER REFERENCES teams(id),
  starts_at INTEGER NOT NULL,   -- ms
  status TEXT NOT NULL DEFAULT 'scheduled',  -- scheduled, live, final, postponed
  home_score TEXT,              -- text: "3", "287/6", "P1"
  away_score TEXT,
  detail TEXT,                  -- "Lap 34/57", "41.2 ov", "FT", "Verstappen P1, Norris P2, Leclerc P3"
  venue TEXT,
  hide_score INTEGER NOT NULL DEFAULT 0,     -- 1 = he hasn't watched it yet: the page hides the result until he taps
  live_url TEXT,                -- an allowlisted JSON URL Live polls during the match
  source_id TEXT,               -- the event id at the source
  updated INTEGER               -- ms
);
CREATE INDEX IF NOT EXISTS matches_when ON matches(starts_at);
