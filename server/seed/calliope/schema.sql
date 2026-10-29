-- Zeus's Calliope tables, unchanged, so the journal moved over from Zeus opens as it was.
-- entries.at / notes.updated / filed.at / lookbacks.at: ms since epoch. entries.audio: audio/<stamp>.m4a in this app.
CREATE TABLE IF NOT EXISTS entries (id INTEGER PRIMARY KEY, at INTEGER, raw TEXT, audio TEXT, seconds INTEGER, filed INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS notes (topic TEXT PRIMARY KEY, title TEXT, about TEXT, updated INTEGER);
CREATE TABLE IF NOT EXISTS filed (id INTEGER PRIMARY KEY, entry INTEGER, topic TEXT, at INTEGER, text TEXT);
CREATE TABLE IF NOT EXISTS lookbacks (id INTEGER PRIMARY KEY, week TEXT, text TEXT, at INTEGER);
