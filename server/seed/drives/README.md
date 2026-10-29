# Drives

For Athena. Zeus's Odyssey drive reports, as facts from the phone. The phone senses; you read and explain.

## Tables (phone tables: only the phone's facts write them; fix a wrong row with `drives_fix`)
- `trips (id, start, end, miles, minutes, points, events, max_mph, score)`
  - `start` / `end`: epoch ms. `points`: JSON `[[lat, lng, t_ms, speed_m_s], ...]`, one every 5 s and 15 m
    (drives from before 2026-10-27 and from Zeus have `[lat, lng, t_ms]`, no speed).
  - `events`: JSON `[{kind, t, lat, lng, value}]` the phone felt during the drive. `kind`: `hard_brake`, `rapid_accel`,
    `sharp_turn` (`value` = peak g), `speeding` (`value` = mph), `phone_handling` (picked up while moving, `value` 0).
    NULL = recorded before the phone sensed events: say "no events recorded", never a score.
  - `max_mph`: the top GPS speed. `score`: 0-100, Zeus's formula (100 minus the events' points per 10 km; a short drive
    counts as 10 km; points: hard_brake 6, rapid_accel 4, sharp_turn 4, speeding 5, phone_handling 3). NULL with `events`.
- `stays (start, end, lat, lng, place)`: where he stopped. `places (name, lat, lng, radius_m, kind)`: his named places,
  yours to fill; the page names a drive's ends from `places`, then `stays`.

## How the phone senses (dev.apollo.senses.DriveSense)
- Only while the car's Bluetooth is connected: GPS once a second, accelerometer and gyroscope at ~50 Hz.
- Braking and acceleration: 0.3 g along the car held 0.4 s; sharp turns 0.35 g across it. The phone learns its mount from
  GPS speed changes; until it has (or when it isn't mounted) braking and launches come from GPS alone.
- Speeding has no speed-limit lookup: 10 s at more than 10 mph over the road's usual speed (estimated from the last
  3 minutes) or above 80 mph. So a "speeding" is a guess about the limit; say it that way.

## Pages
- `pages/index.html`: this week's totals and average score (weighted by minutes), each drive (date, times, from -> to,
  miles, minutes, score), and a drive's report on tap: the route with its events, counts per kind, top speed.
  Numbers in the text font (he asked: no dot-matrix digits here).

## Street map under a route
Any page can draw a drive on a real dark street map (TomTom tiles through Apollo's server; nothing else leaves the phone):
```html
<div id="map" style="height:260px"></div>
<script src="/kit/apollomap.js"></script>
<script>
var pts = JSON.parse(trip.points).map(function (p) { return [p[0], p[1]]; });
apollomap.show("#map", {route: pts, fit: true, markers: [
  {lat: pts[0][0], lng: pts[0][1], color: "--quiet", label: "Start"},
  {lat: ev.lat, lng: ev.lng, color: "--alarm", label: "Hard brake"}]});   // one per event, colored by kind
</script>
```
`show` returns a Promise of the Leaflet map; call it again on the same element to redraw. `still: true` makes a small
preview that doesn't pan or zoom (a list thumbnail); keep thumbnails few, each one loads tiles. Options: docs/API.md
"Street maps on pages".

## Extending
Spots where the same event keeps happening (Zeus's hotspots: 2+ of one kind within 60 m), a weekly driving card,
commute times from `places`.
