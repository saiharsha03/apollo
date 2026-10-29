-- A synced copy of owner.db's tasks (Iris's shape, docs IRIS-DB-AND-TASKS section 4) plus lists and the sync log.
CREATE TABLE IF NOT EXISTS lists (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,    -- Groceries, Home, Car... (a project in owner.db)
  about TEXT
);

CREATE TABLE IF NOT EXISTS tasks (
  id INTEGER PRIMARY KEY,
  iris_id INTEGER UNIQUE,       -- owner.db tasks.id; NULL until Iris has taken a new row in
  title TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',   -- pending, done, cancelled
  priority TEXT DEFAULT 'medium',           -- low, medium, high
  due_date TEXT,                -- YYYY-MM-DD or NULL
  context TEXT,                 -- short label; money, legal, health = private (hidden unless he opens them)
  list_id INTEGER REFERENCES lists(id),
  notes TEXT,
  owner TEXT NOT NULL DEFAULT 'owner',     -- owner, iris, atlas
  blocked_on_owner INTEGER NOT NULL DEFAULT 0,
  created INTEGER,              -- ms
  updated INTEGER,              -- ms
  dirty INTEGER NOT NULL DEFAULT 0,         -- 1 = changed here (by Athena or his tap), not yet taken back into owner.db
  -- GTD (Zeus's tasks): where a pending task stands. inbox = not clarified yet; next = the next action; waiting = on
  -- someone else (waiting_on, follow_up); someday = parked. Done and cancelled stay in status.
  stage TEXT NOT NULL DEFAULT 'inbox',      -- inbox, next, waiting, someday
  waiting_on TEXT,              -- who or what it waits for
  follow_up TEXT                -- YYYY-MM-DD to chase it
);
CREATE INDEX IF NOT EXISTS tasks_open ON tasks(status, due_date);

-- Every change the sync applies, either way. Iris writes it; nobody edits or deletes it.
CREATE TABLE IF NOT EXISTS sync_log (
  id INTEGER PRIMARY KEY,
  at INTEGER NOT NULL,          -- ms
  direction TEXT NOT NULL,      -- to_app (owner.db -> here) or to_iris (here -> owner.db)
  task_id INTEGER,
  iris_id INTEGER,
  change TEXT NOT NULL,         -- "done", "added", "due 2026-10-28", "title"...
  who TEXT                      -- iris, athena, owner
);

-- The weekly review (GTD): one row a week, written by Athena after she and he went through it.
CREATE TABLE IF NOT EXISTS reviews (
  week TEXT PRIMARY KEY,        -- YYYY-Www
  at INTEGER,                   -- ms
  clarified INTEGER,            -- inbox items given a stage
  closed INTEGER,
  waiting INTEGER,              -- waiting-for items chased
  note TEXT
);
