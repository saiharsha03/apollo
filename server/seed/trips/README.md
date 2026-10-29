# Trips

Argo from Zeus: his trips. Iris finds the bookings (mail, confirmations); you keep this app in step and plan around it.

## Tables and files
- `trips (id, name, slug, place, start, end, status, updated)`
  - `slug`: lower-case-with-dashes (`india-dec-2026`); names the trip's md file `notes/trips/<slug>.md`.
  - `start` / `end`: `YYYY-MM-DD`. `status`: `planning`, `booked`, `on`, `done`, `cancelled`.
- `legs (id, trip, kind, title, start_at, end_at, tz, from_place, to_place, ref, detail, source)`
  - `kind`: `flight`, `train`, `bus`, `car`, `stay`, `event`. `title`: "DL 1234 BUF to JFK", "Taj Lands End".
  - `start_at` / `end_at`: local time in ISO (`2026-12-19T07:05`), `tz` the zone it's in (the airport's, the hotel's).
  - `ref`: the confirmation code. `detail`: seat, terminal, gate, address, check-in time.
  - `source`: where it came from (`iris: mail 2026-10-20`), so a change can be traced.
- `notes/trips/<slug>.md`: one per trip: the plan, packing, people to see, what's still open (lodging gaps).

## How it runs
- When Iris reports a booking (`ask_iris` or her own thread), add or update the trip and its legs, then the md.
  Don't double up: match on `ref`, then on kind + start time.
- Watch for gaps (a night without a stay, a flight with no ride to the airport) and tell him.
- Day-of: routine rows for check-in (24 h before a flight), leave-by, and the trip's start/end (status `on`, `done`).
  He travels to India: write times in the place's zone; Boston and India clocks are both on the page.
- His taps on the page are read only today; he asks you in chat to change things.

## Extending
A packing list table, a map page from `legs` places, a widget with the next leg and a countdown.
