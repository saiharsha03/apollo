# Sports (Olympia)

For Athena. docs/PLAN.md, Sports. You own every table, note, page and widget here.

## Tables
- `teams`: who he follows (`following = 1`), per sport, with the team's own colour (`#RRGGBB`) and the id the source
  uses. A driver is a team of one.
- `matches`: fixtures and results. Races have a `title` and no home/away; the podium goes in `detail`. `status` is
  scheduled, live, final or postponed. Scores are text (cricket "287/6", F1 "P1").
- `hide_score = 1`: spoiler-safe. Set it for a match he said he'll watch later; the page and widget show "Result hidden"
  until he taps it. Never put a hidden result in a card, a notification or a digest; clear it when he says he watched.

## Filling it
Fixtures: once a week (a routine row you add, Sunday night) for every followed team, the next 14 days. Results: after
each match ends. Keep `source_id` so a refresh updates the row (`UPDATE ... WHERE source_id = ?`), never a duplicate.

Score sources (each host must be on Owner's fetch allowlist before a page or Live can reach it; only he adds hosts,
in Approvals, with his fingerprint; ask him, never work around it):
- `api.jolpi.ca`: F1 calendar, results and standings (the Ergast successor, `/ergast/f1/current.json`).
- `api.openf1.org`: F1 live timing (positions, laps) during a session.
- `site.api.espn.com`: scoreboards for football (`soccer/eng.1`), NFL, NHL, NBA, cricket
  (`/apis/site/v2/sports/<sport>/<league>/scoreboard`).
None of these are on the allowlist today. Until they are, you fill the tables from your own sandbox fetches; the page
only reads tables.

## Live
Live is Apollo's generic Now Bar chip (a foreground service with a Live Update notification, on only during a live
match or drive, stopping itself after). For a match he wants live: set `status = 'live'` and `live_url` to an
allowlisted JSON URL for that event, and ask the phone to start Live on it (`phone_do` with the live action once the
engine has it). Apollo polls the URL and shows the score; you update the row at the end (`final`, scores, detail).

## Look
Box Box (the F1 widget app): one dark hero card for what's next with a big dot-matrix countdown, team colour as a thin
strip, everything else plain light rows. Team colours are the only colours; red stays for "live".
- `pages/index.html`: next up, live now, the next fixtures, recent results, the teams he follows.
- `widgets/next.html`: the next match of a followed team with a countdown.
Make new widgets in that look (podium, standings, a driver's season) when he asks.
