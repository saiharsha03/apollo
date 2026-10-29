# Muse

For Athena. Zeus's music section, renamed (docs/PLAN.md, Music). Apollo has the native Spotify SDK; this app is the
record and your notes.

## Who owns what
- `plays` is a **phone table** (app.json `phone_tables`): the phone sends a `music` fact per track and the server's
  ingester writes the row. You can read it, never write it.
- You own `notes/music.md`, the pages, the widgets, any new tables you add (a `liked` or `moods` table, say) and the
  music rows in the routines table.

## Tables
- `plays`: at (ms), track, artist, album, uri (`spotify:track:...`), seconds listened, source.

## Routines
Music routines are rows in the routines app's `routines` table, like every other routine. Nothing is planted (he
hasn't asked for any yet). The first one he's likely to want, added only when he asks:
`INSERT INTO routines(name, when_trigger, action, why) VALUES('Spotify on when the car connects', 'car:connected',
'Ask the phone to resume Spotify (phone_do spotify, play) unless something else is already playing', '<his words>')`.
Others work the same way (a playlist at bedtime; pause when he falls asleep: trigger `sleep:asleep`).

## Actions
Playing, pausing, skipping and volume are `phone_do` (`spotify`, `pause_media`, `volume`): free with Undo.
`phone_do spotify` also takes `do`: `shuffle` {on?: true|false; none toggles}, `queue` {uri: spotify:track/episode},
`like` / `unlike` {uri?: spotify:track; none = the song playing}, `devices` (his Spotify Connect devices), `device`
{device: an id from devices} (moves playback there and plays). The `spotify` tool's `devices` lists them too, and
`scopes` says whether his sign-in allows devices and moving playback (if not, he signs in to Spotify again on the phone).

## From a page
A page controls Spotify with `apollo.tap("spotify", {do, ...})`: the same args as `phone_do spotify`, carried out under
his spotify rule (free today); its promise gives `{ok, detail}`. Examples:
- `apollo.tap("spotify", {do: "pause"})`, `{do: "next"}`, `{do: "play", uri: "spotify:playlist:..."}`
- `apollo.tap("spotify", {do: "shuffle", on: true})` (leave out `on` to toggle)
- `apollo.tap("spotify", {do: "queue", uri: "spotify:track:..."})`
- `apollo.tap("spotify", {do: "like"})` / `{do: "unlike", uri: "spotify:track:..."}`
- `apollo.tap("spotify", {do: "devices"}).then(r => r.ok && JSON.parse(r.detail))` gives `[{id, name, type, active, volume}]`
  (no fact for this one: it is a read); then `apollo.tap("spotify", {do: "device", device: id})`.
Any other label (or bad args) is a plain page_tap fact for you, as on every page.

The first control after install asks Spotify to allow Apollo: Spotify opens with its Allow screen (Agree once) and the
control then runs; that tap's promise can take up to a minute. A failure's `detail` says what to fix (not installed, not
logged in, Allow refused); show it as is.

Reading his Spotify from the page needs no turn of yours: `apollo.spotify(kind, args)` asks the server's Web API through
the phone and resolves `{ok, detail}`; when ok, `detail` is a JSON list, else why it failed (not signed in on the server,
a refusal). Each item is `{name, uri, type, art, by?, album?, minutes?, owner?, tracks?}`; `art` is an i.scdn.co URL.
- `apollo.spotify("search", {query: "arijit", type: "track"})` (type track | artist | album | playlist; limit up to 10)
- `apollo.spotify("playlists")` (his playlists, up to 50)
- `apollo.spotify("playlist_tracks", {playlist: "spotify:playlist:...", offset: 0})` (50 songs a page)
Example: `apollo.spotify("playlists").then(r => r.ok ? draw(JSON.parse(r.detail)) : say(r.detail))`. Tap a result to play
it with `apollo.tap("spotify", {do: "play", uri})`. Nothing else is readable this way, and it posts no fact.

## Extending
- `pages/index.html`: what he played last, minutes a day this week, top artists in 30 days, the recent list.
- `widgets/recent.html`: the last track and today's minutes.
Put what you learn about his taste (moods by time of day, what he skips, drive music) in `notes/music.md`.
