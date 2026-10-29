# Calliope

His journal (Owner, 2026-10-25: "anything I type there goes to Athena"), moved over from Zeus with the same tables,
so his old entries, notes and voice notes open here as they were.

He writes on the Write tab and taps Done. The entry goes to the server as a `page_write` fact
(`INSERT INTO entries (at, raw, audio, seconds) ...`) and the page tells you `CALLIOPE ENTRY at <ms>` (a `page_tell`
fact). Then, for each entry:

1. Read it: `SELECT id, raw, audio FROM entries WHERE filed = 0` (or `WHERE at = <ms>`).
2. Clean it: fix typos, spelling, grammar and punctuation. Keep his words, phrasing, slang and mood. Never make it
   formal, shorter or yours.
3. Split it by topic and file each piece: a row in `filed (entry, topic, at, text)`, and the topic in
   `notes (topic, title, about, updated)` (create it the first time). Topics are lower-case-with-dashes (work,
   health, family, ideas); make, merge and rename them as you see fit.
4. Mark it: `UPDATE entries SET filed = 1 WHERE id = ?`.
5. Keep `notes/<topic>.md` in step (dated entries, his cleaned words) so his notes are plain markdown he can take anywhere.
6. Lasting facts about him go to the memory app too (`source` = `calliope entry <id>`).

## Tables
- `entries (id, at, raw, audio, seconds, filed)`: what he wrote, as he wrote it. `audio`: `audio/<stamp>.m4a` when it
  was a voice note, `seconds` its length. Voice notes are kept on his phone (the app's phone folder, with Zeus's old
  ones); you never get the audio. Sarvam writes the words when the server is reachable: they are in `raw` after
  anything he typed, and the page's tell says so. A transcript can mishear: clean it like his typing. An entry with
  only audio and no words (recorded offline) is filed as "voice note, N s" under the topic he's been on (or left for him).
- `notes (topic, title, about, updated)`: one row per topic.
- `filed (id, entry, topic, at, text)`: the cleaned pieces, by topic.
- `lookbacks (id, week, text, at)`: each week write a short look-back of what he journaled (`week` = `2026-W44`);
  the Notes tab shows the latest. A weekly routine row is yours to add.

## Just for me
"Just for me" pages are his alone: the button hands over to Apollo's own sealed page and they go into Calypso on the
phone. They never reach this app, its tables, the server or you, and that is on purpose. Don't ask about them.

Other apps of yours read these tables with `zeus.peek("calliope", "SELECT ...")`.
