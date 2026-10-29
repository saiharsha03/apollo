# Usage (Metron)

For Athena, Iris and Atlas. docs/PLAN.md, Usage: one page over one table.

## Tables
- `usage`: one row per reading, never updated in place: at (ms), who (athena, iris, atlas), meter, used_pct or
  used_usd, cap_usd, resets_at (ms), detail, source. The page shows the newest row per (who, meter).

Meters:
| who | meter | filled with |
|---|---|---|
| athena | `managed_month` | `used_usd` and `cap_usd` from the `usage` tool (`spent_usd`, `cap_usd`; `source` = its `source`, "Anthropic cost report" when the server has the admin key, else "own tally"), `resets_at` = the 1st of next month |
| iris | `claude_5h`, `claude_7d` | `used_pct` and `resets_at` from her own Claude Code limits (what her statusline shows) |
| atlas | `codex_5h`, `codex_week` | `used_pct` and `resets_at` from Codex's rate-limit windows |

## Who writes
Each of you writes your own rows with `app_sql`, one `INSERT` per reading:
- Athena: when you call `usage` anyway (each morning, and before a long job). Don't call it just to fill this table.
- Iris and Atlas: from your own hooks, at most every 15 minutes, only when the number moved.
- Keep it small: once a month, delete rows older than 90 days (with why).

## When the credit runs out
At $200 (or a credit error from the API) the server makes Iris the brain until the 1st. Put that in the
`managed_month` row's `detail` ("brain = iris until Nov 1") so the page says it.

## Alerts and the 90% rule (Metron, restored from Zeus)
- Every 5 minutes the server reads the newest row per (who, meter) and raises a Cerberus alert once per window: at 80%
  (normal) and at 95% (high: it goes straight to his phone). `alerts` lists them; resolve them when the window resets.
- **No job above 90%:** while any of Iris's or Atlas's windows is at 90% or more, `ask_iris` / `ask_atlas` refuse a new
  job and say which window. Do it yourself, wait for the reset, or tell him. Keep your own long jobs off when the
  `managed_month` row is past 90% of the cap.
- So the numbers must be fresh: Iris and Atlas write their rows as above.

## Extending
- `pages/index.html`: the credit card, then one pill bar per window, red at 90% and over.
- `widgets/credit.html`: "$X of $200" with a bar. He may ask for other styles (pill bars like his statusline): build
  them as more widgets over the same table.
