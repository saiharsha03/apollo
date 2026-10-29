# Pixel characters

Apollo's characters are dot-matrix sprites: Athena the owl, Iris the cloud, Atlas the titan. The phone shows them
doing what each agent is really doing (the tool it is on). Mini-app pages can draw them too, along with any new
characters and scenes an app ships. Everything is plain data: one JSON file per character.

The copy at `server/seed/pixel-demo/README.md` must stay the same as this file (a server test checks it), so Athena
can read it with `app_read pixel-demo README.md`.

## Character files

Built-in: `owl`, `cloud`, `titan`, shipped in the app (`app/src/main/assets/pixel/`). An app can ship its own as
`pixel/<name>.json` in its folder (write it with `app_write`). Where both have the same name, the app's own file wins.

```json
{
  "format": 1,
  "name": "rain",
  "w": 12,
  "h": 10,
  "palette": {"+": "#A39A8C", "#": "#F2EDE4"},
  "scenes": {
    "fall": {"ms": 120, "frames": [
      ["............", "...the rows...", "..."],
      ["..."]
    ]}
  }
}
```

- `w`, `h`: every frame's size in dots, 1 to 64 each.
- `palette`: up to 16 one-character keys mapped to `#RGB`, `#RRGGBB` or `"red"` (Apollo's one red, taken from the
  app's theme). `.` is always empty (see-through). Keep to Apollo's look: near-monochrome (`#F2EDE4` ink, `#A39A8C`
  grey, `#5E574D` dim, `#0E0D0C` dark for eyes and screens) plus `"red"` as a tiny accent only.
- `scenes`: up to 40, each named with `a-z 0-9 _ -` (1 to 40 characters). `ms` is how long each frame shows, from 40
  to 10000 (default 200). `frames`: 1 to 32 frames, and each frame is exactly `h` strings of exactly `w` characters,
  each of which is `.` or a palette key. Scenes loop.
- Whole file: 256 KB at most.

Fail-soft rules: a scene with a wrong row count, a wrong row length or an unknown character is dropped (the page's
console says which). A palette entry with a bad key or colour is ignored, so the scenes that use it are dropped too.
A file with no usable scene draws nothing. Nothing throws.

The built-in files are 24 x 20 dots, the character at the bottom-left doing the scene with its right arm, the props
(laptop, book, telescope, easel, envelope, phone...) touching it on the right. Where the body sits in the frame: owl
14 x 14 at dot (0, 6); cloud 12 x 10 at (1, 10), with two little arms; titan 12 x 12 at (0, 8). Built-in scenes:
`stand` (just the character), `typing`, `reading`, `telescope`, `painting`, `mail`, `phone`, `note`, `coins`,
`talking`, `working`, `tea`, `sleep`. The tool-to-scene table the phone uses is
`app/src/main/java/dev/apollo/pixel/ToolScenes.kt`. The built-ins were drawn by `art/pixel/make.py` (it rewrites
the three files, so a hand edit to them is lost if it runs again).

## On a page: `apollo.pixel`

Every mini-app page and widget has it (it is served with the `apollo` shim). Canvas only; nothing to import.

- `apollo.pixel.load(name)`: a Promise of a character, or `null`. `name` is a file name (`"rain"`), a built-in
  (`"owl"`, `"cloud"`, `"titan"`) or an agent (`"athena"`, `"iris"`, `"atlas"`). It also accepts a character object
  (the JSON above) directly. The result is cached per name.
- `apollo.pixel.draw(canvas, {character, scene, frame, time, x, y, scale, flip, matrix})`: draws one still frame,
  returning a Promise of true or false. `x`, `y` are in dots, and `scale` is dot size in pixels (default: fit the
  canvas). `frame` picks a frame; otherwise `time` (ms, default now) does. `draw` adds to the canvas and never clears
  it.
- `apollo.pixel.stage(canvas, {w, h, dot, matrix, background, time, actors})`: several characters on one dot grid,
  animated until `stop()`.
  - `w`, `h`: the stage in dots (up to 200 each). `dot`: pixels per dot (default: fit the canvas).
  - `matrix` (default true) draws the unlit dots; set false for small sizes. `background` defaults to `#1C1A17`;
    `null` makes it see-through.
  - `actors`: up to 12, drawn in order (later ones on top). Each is `{character, scene, x, y, flip, moves, loop,
    delay}`. `moves` is a list of `{x, y, ms}` steps from the start position, walked in straight lines on whole dots.
    The path loops unless `loop: false`; to loop smoothly, end it back where it started. `delay` holds the actor for
    some ms first.
  - `time`: draw one still at that ms instead of animating (good for widgets, which can't animate).
  - Returns `{ready, actors, render(t), stop()}`. `ready` is a Promise of how many actors loaded. Change an actor's
    `scene`, `x`, `y` or `moves` any time and the next frame shows it.

Size the canvas in device pixels for crisp dots: `canvas.width = w * dot`, with `dot` from
`clientWidth * devicePixelRatio / w`.

## Example: Iris rains near Athena

`pixel/rain.json` (the drops: four frames, each the last shifted down a row):

```json
{"format": 1, "name": "rain", "w": 12, "h": 10, "palette": {"+": "#A39A8C"}, "scenes": {"fall": {"ms": 120, "frames": [
  [".+...+...+..", "............", "...+...+...+", "............", ".+...+...+..", "............", "...+...+...+", "............", ".+...+...+..", "............"],
  ["............", ".+...+...+..", "............", "...+...+...+", "............", ".+...+...+..", "............", "...+...+...+", "............", ".+...+...+.."],
  ["...+...+...+", "............", ".+...+...+..", "............", "...+...+...+", "............", ".+...+...+..", "............", "...+...+...+", "............"],
  ["............", "...+...+...+", "............", ".+...+...+..", "............", "...+...+...+", "............", ".+...+...+..", "............", "...+...+...+"]
]}}}
```

`pages/home.html`:

```html
<canvas id="stage" style="width:100%"></canvas>
<script>
  var c = document.getElementById('stage'), dot = Math.floor(c.clientWidth * devicePixelRatio / 40);
  c.width = 40 * dot; c.height = 26 * dot;
  var drift = [{x: 18, ms: 3000}, {x: 18, ms: 1500}, {x: 2, ms: 3000}, {x: 2, ms: 1500}];
  apollo.pixel.stage(c, {w: 40, h: 26, dot: dot, actors: [
    {character: 'cloud', scene: 'stand', x: 2, y: -10, moves: drift},       // built-in: the cloud sits at (1, 10), so y -10 is the top
    {character: 'rain', scene: 'fall', x: 3, y: 10, moves: drift.map(function (m) { return {x: m.x + 1, ms: m.ms}; })},
    {character: 'owl', scene: 'stand', x: 25, y: 6}                         // bottom right, dry for now
  ]});
</script>
```

This is the `pixel-demo` app (`server/seed/pixel-demo`). To make a new character, draw its 12 x 12 or so in the
palette characters, then add one scene per thing it does, each a few frames that change by a dot or two (eyes, a
prop, a one-dot bob). Small moves read best at this size.
