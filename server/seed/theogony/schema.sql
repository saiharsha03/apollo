-- asked / answered_at: ms since epoch. chips: a JSON list of one-tap answers. multi = 1 lets him pick several chips.
CREATE TABLE IF NOT EXISTS questions (
  id INTEGER PRIMARY KEY,
  question TEXT NOT NULL,
  why TEXT,
  topic TEXT,
  chips TEXT,
  multi INTEGER NOT NULL DEFAULT 0,
  asked INTEGER,
  answer TEXT,
  answered_at INTEGER,
  skipped INTEGER NOT NULL DEFAULT 0,
  filed INTEGER NOT NULL DEFAULT 0
);
-- The first questions, from Zeus's getting-to-know-you interview (the ones that don't stay on the phone).
INSERT INTO questions (question, why, topic, chips, multi, asked) VALUES
 ('Which days are you usually in the office?', 'Commute checks only on the days you go in.', 'routines', '["Monday","Tuesday","Wednesday","Thursday","Friday","Fully remote"]', 1, strftime('%s','now') * 1000),
 ('Who''s family, and who of them is in India? Labels are fine.', 'For the two clocks, birthdays and call-home.', 'family', '["Parents in India","A sibling in India","Family here in the US"]', 1, strftime('%s','now') * 1000),
 ('Which notifications always matter?', 'What always breaks through.', 'notifications', '["Family chats","Work chat","Work email","Bank alerts"]', 1, strftime('%s','now') * 1000),
 ('And which can I keep quiet?', 'What never needs you.', 'notifications', '["Promotions","Social media","Shopping apps","News","Games"]', 1, strftime('%s','now') * 1000),
 ('What do you drive, and is it leased?', 'The lease pace and the car log.', 'car', '["Leased","Owned","No car"]', 0, strftime('%s','now') * 1000),
 ('Any parking rule I should know: a garage, street sweeping, a permit?', 'Where-I-parked and street-sweeping reminders.', 'car', '["Garage at home","Street parking","No rule"]', 0, strftime('%s','now') * 1000),
 ('Which teams do you follow?', 'Live scores and match-day cards.', 'sports', '[]', 0, strftime('%s','now') * 1000),
 ('Do you play games with friends? Which ones?', 'Game nights and who you play with.', 'games', '["PUBG","No games"]', 1, strftime('%s','now') * 1000),
 ('Anything I should never bring up, or never keep?', 'So I stay out of it.', 'privacy', '["Nothing in particular"]', 0, strftime('%s','now') * 1000);
