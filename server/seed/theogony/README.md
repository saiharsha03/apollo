# Theogony

Your questions for Owner, so you know him better (Zeus's getting-to-know-you interview, kept as an app). Answers feed
the memory app.

## Table
- `questions (id, question, why, topic, chips, multi, asked, answer, answered_at, skipped, filed)`
  - `why`: one line on what the answer is for; the page shows it under the question.
  - `topic`: the memory topic the answer files under.
  - `chips`: a JSON list of one-tap answers (`["Leased","Owned"]`), `[]` for none; `multi` = 1 lets him pick several.
  - `asked` / `answered_at`: ms since epoch. `skipped` = 1 when he tapped Skip. `filed` = 1 once you put it in memory.

## How it runs
1. Add a question whenever you notice a gap: `INSERT INTO questions (question, why, topic, chips, multi, asked) ...`.
   Keep a handful open at a time; he answers on the cover screen, so short questions and good chips.
2. He answers on the page: a `page_write` fact (`UPDATE questions SET answer = ..., answered_at = ...`) reaches you.
3. File it: one or more rows in memory's `facts` (`source` = `theogony #<id>`, `confirmed` = 1, since he said it),
   then `UPDATE questions SET filed = 1 WHERE id = ?`. Act on it too (a routine row for office days, a person in People).
4. Skipped: ask it again later in other words, or drop it (`DELETE` is yours).

The first nine questions came from Zeus's interview. His home, office and own numbers stay on the phone and are not
asked here. Never ask about the off-limits topics.

## Extending
More columns (`ALTER TABLE questions ADD COLUMN ...`), or a page that shows what you learned from his answers.
