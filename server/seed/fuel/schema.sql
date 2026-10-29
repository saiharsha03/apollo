-- Fill-ups. Zeus's shape (odyssey FillUp: at, place, cents, gallons, price_per_gal, card, odometer) with plain names.
CREATE TABLE IF NOT EXISTS fills (
  id INTEGER PRIMARY KEY,
  at INTEGER NOT NULL,          -- ms
  station TEXT,                 -- Zeus "place"
  gallons REAL,
  price REAL,                   -- dollars a gallon (Zeus price_per_gal)
  usd REAL,                     -- the total paid (Zeus cents / 100)
  odometer INTEGER,             -- miles on the car at this fill
  card TEXT,                    -- "Chase 1234": a card name and at most four digits, never more
  source TEXT DEFAULT 'owner', -- owner (his tap), athena, iris, zeus (the import)
  note TEXT
);
CREATE INDEX IF NOT EXISTS fills_at ON fills(at);
