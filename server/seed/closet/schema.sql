-- items: one piece of clothing. photo: a path inside this app (photos/<file>.jpg), saved there by Apollo.
-- kind: top, bottom, outer, shoes, accessory. warmth: 1 (summer) to 5 (deep winter). added / last_worn: ms since epoch.
CREATE TABLE IF NOT EXISTS items (
  id INTEGER PRIMARY KEY,
  photo TEXT,
  kind TEXT NOT NULL,
  name TEXT,
  color TEXT,
  warmth INTEGER,
  notes TEXT,
  added INTEGER,
  last_worn INTEGER
);
-- outfits: what Athena suggests (or he wore). items: a JSON list of item ids. weather: the conditions it was for, in words.
-- date: YYYY-MM-DD. worn = 1 when he taps "Wearing this".
CREATE TABLE IF NOT EXISTS outfits (
  id INTEGER PRIMARY KEY,
  date TEXT,
  items TEXT NOT NULL,
  weather TEXT,
  why TEXT,
  worn INTEGER NOT NULL DEFAULT 0
);
