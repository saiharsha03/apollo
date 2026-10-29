-- steps: the checklist (Athena's). key ties a step to Apollo's status row when Apollo can see it for itself.
-- done = 1 when he or Athena marks it; a step also counts as done while its status row says ok.
CREATE TABLE IF NOT EXISTS steps (
  id INTEGER PRIMARY KEY,
  key TEXT UNIQUE,
  step TEXT NOT NULL,
  why TEXT,
  fix TEXT,
  done INTEGER NOT NULL DEFAULT 0,
  sort INTEGER NOT NULL DEFAULT 100
);
-- status: written by Apollo only (`setup` facts): ok = 1 when the thing is in place right now. at: ms since epoch.
CREATE TABLE IF NOT EXISTS status (
  key TEXT PRIMARY KEY,
  ok INTEGER NOT NULL DEFAULT 0,
  detail TEXT,
  at INTEGER
);
INSERT OR IGNORE INTO steps (key, step, why, fix, sort) VALUES
 ('pair', 'Pair with the server', 'Nothing reaches Athena until the phone is paired.', 'More > Server: type the four-word code that deploy/pair.sh printed on the VPS.', 10),
 ('brain_key', 'Hand Athena her key', 'Athena runs on the Anthropic key kept in Calypso.', 'More > Server > Send key, then your fingerprint. The admin key goes too if Calypso has it (real costs on Usage).', 20),
 ('box_key', 'Athena''s box key', 'Her tools run in the apollo-box environment.', 'Console > Managed Agents > Environments > apollo-box > Generate key, then paste it in More > Server > Send box key.', 30),
 ('notifications', 'Notification access', 'She reads messages and alerts (full text, never codes).', 'More > Senses > Notifications, then turn Apollo on.', 40),
 ('usage', 'Usage access', 'Unlocks, app use and sleep come from it.', 'More > Senses > Usage, then turn Apollo on.', 50),
 ('location', 'Location, Bluetooth and alerts', 'Drives, the car, and Apollo asking you things.', 'More > Senses > Location, Bluetooth, alerts: allow each.', 60),
 ('background_location', 'Location all the time', 'A drive records with the screen off.', 'More > Senses > Location all the time > Allow all the time.', 70),
 ('car', 'Pick your car', 'The car''s Bluetooth starts and ends a drive.', 'More > Senses > Your car > Pick, then tap the car.', 80),
 ('health', 'Health Connect', 'Days and walks for the health app.', 'Settings > Health Connect > App permissions > Apollo: allow steps, sleep and exercise.', 90),
 ('calendar', 'Calendar', 'Your Google calendar and the phone calendar, read only.', 'Settings > Apps > Apollo > Permissions > Calendar > Allow.', 100),
 ('battery', 'Battery: unrestricted', 'Android stops background apps; drives and facts need Apollo awake.', 'Settings > Apps > Apollo > Battery > Unrestricted.', 110),
 ('google_home', 'Google Home', 'Lights, plugs and fans from the Home screen.', 'More > Home: sign in with Google and allow Apollo.', 120),
 ('spotify', 'Spotify', 'Muse plays your music, and routines like Spotify on in the car.', 'Open Muse once and allow Apollo in Spotify.', 130),
 ('widget', 'Add the widget', 'Home-screen views Athena designs for you.', 'Long-press the home screen > Widgets > Apollo, then pick what it shows.', 140),
 ('iris', 'Iris''s plugins', 'Iris joins the chat through two plugins: the group and her private chat.', 'On the VPS: deploy/iris-plugin-install.sh (Iris can run it).', 150),
 ('zeus_import', 'Zeus''s apps moved over', 'Calliope''s journal and the other Zeus apps come over once.', 'Happens by itself after pairing. If it stays open, tell Athena.', 160),
 ('call_log', 'Calls', 'Your call log and WhatsApp calls fill the routines app (call-home nudges).', 'More > Senses > Calls > Allow.', 95),
 ('contacts', 'Contacts', 'Names for calls and texts; a birthday Athena saves goes on your Google contact after your tap.', 'More > Senses > Contacts > Allow.', 94),
 ('activity', 'Walking', 'Athena hears when you start and stop walking.', 'More > Senses > Walking > Allow.', 96),
 ('health_write', 'Food and weight logging', 'Athena can log a meal or a weight to Health Connect, each after your tap.', 'More > Senses > Health Connect: allow writing nutrition and weight.', 97),
 ('docs_folders', 'Folders for Athena (optional)', 'PDF, Word and text files in folders you pick go to Athena.', 'More > Senses > Folders for Athena > Add, then pick the folder.', 170);
