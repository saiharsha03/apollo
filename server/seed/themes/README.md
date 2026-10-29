# Themes

Apollo's look, as files. Each `<name>.json` here is one theme; `default.json` is the look Apollo was built with
(2026-10-26). Owner picks one through you: `phone_setting` with `name: theme`, `value: <name>` switches every native
screen at once. A file that is missing or bad falls back to the default, and the phone notes it in its log
(`phone_logs`, area `theme`). The default is also built into the app, so it always works.

Only make a new theme when Owner asks for one. Keep to his look rules: near-monochrome, one accent colour for actions,
red only for what needs him, no gradients for decoration.

## The format
```json
{
  "name": "default",
  "about": "one line: what this look is",
  "light": { ...colours... },
  "dark":  { ...colours... },
  "fonts": { "text": "figtree", "numbers": "doto" },
  "radius": 22,
  "sky": true
}
```
- `light` and `dark`: two sets of colours. Apollo is dark only (Owner, 2026-10-27): it draws with `dark` whatever the
  phone's mode, so make `dark` a dark look; `light` is kept for the format. `dark` may be left out (then `light` is
  used). Every colour below must be there, as `#RRGGBB` or `#AARRGGBB` (alpha first):
  - `ground`: the surface behind every screen but Home.
  - `raised`: a panel, card or bubble on the ground.
  - `soft`: her bubbles and quiet fills.
  - `ink`: text.
  - `quiet`: secondary text.
  - `line`: thin borders.
  - `accent`: the one action colour (Allow, Send, the selected place). Button text on it is `ground`.
  - `alarm`: red, only for what needs him and errors.
  - `on_sky`, `on_sky_dim`: text over Home's sky and glass (keep them light: the sky is mostly dark or saturated).
  - `mine`: his own chat bubbles (`ink` text sits on it).
  - `sky_off`: Home's plain ground when `sky` is false (under `on_sky` text, so keep it dark).
- `fonts`: only the fonts bundled in the app: `figtree` (words) and `doto` (dot-matrix numbers). `text` is every word,
  `numbers` is times, temperatures and counts.
- `radius`: corner radius in dp for cards (buttons use 6 less), 0 to 40.
- `sky`: true for the living weather sky on Home; false for the plain `sky_off` ground.

Mini app pages have their own `look.css`; a theme changes Apollo's native screens, not pages.
