-- Iris's synced copy of owner.db's money tables. Amounts are dollars. Only the last four digits of a card ever land here.
CREATE TABLE IF NOT EXISTS transactions (
  id INTEGER PRIMARY KEY,
  iris_id TEXT UNIQUE,          -- owner.db transactions.id ("iris:<id>"), so a re-sync updates instead of adding
  date TEXT NOT NULL,           -- YYYY-MM-DD
  amount REAL NOT NULL,
  merchant TEXT,
  category TEXT,                -- Groceries, Eating out, Fuel, Rides and transit, Car, Shopping, Subscriptions, Utilities, Rent and home, Travel, Fun, Health, Transfers, Other
  kind TEXT NOT NULL DEFAULT 'spend',  -- spend, refund, payment, transfer, income, declined
  last4 TEXT,
  status TEXT DEFAULT 'posted', -- posted, pending
  not_counted INTEGER NOT NULL DEFAULT 0,  -- left out of his totals (paid back, between his own accounts)
  note TEXT,
  synced INTEGER                -- ms, when Iris last wrote this row
);
CREATE INDEX IF NOT EXISTS transactions_date ON transactions(date);

CREATE TABLE IF NOT EXISTS bills (
  id INTEGER PRIMARY KEY,
  iris_id TEXT UNIQUE,
  name TEXT NOT NULL,
  amount REAL,
  due_date TEXT,                -- YYYY-MM-DD, the next one
  autopay INTEGER NOT NULL DEFAULT 0,
  paid_at TEXT,                 -- YYYY-MM-DD when this cycle was paid
  note TEXT,
  synced INTEGER
);

CREATE TABLE IF NOT EXISTS subscriptions (
  id INTEGER PRIMARY KEY,
  iris_id TEXT UNIQUE,
  name TEXT NOT NULL,
  amount REAL,
  cycle TEXT DEFAULT 'monthly', -- weekly, monthly, yearly
  next TEXT,                    -- YYYY-MM-DD renewal
  source TEXT DEFAULT 'iris',   -- iris, detected
  active INTEGER NOT NULL DEFAULT 1,
  synced INTEGER
);
