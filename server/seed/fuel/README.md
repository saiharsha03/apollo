# Fuel

For Athena. The fuel log from Zeus, and the drives page (docs/PLAN.md, Driving: "Drives mini app: trips, places,
car/lease, fill-ups"). Drives is a core app the phone fills (`drives.trips`); this app keeps the fill-ups and shows
both, reading `drives.trips` read-only (`zeus.peek("drives", ...)`).

## Who owns what
- `fills`: his taps on the page add rows (`source = 'owner'`); you add or fix rows (`source = 'athena'`), and so does
  Iris when she finds a fill-up in his mail or card alerts (`source = 'iris'`). Iris also keeps her own `fillups` table
  in owner.db; when you add a fill he confirmed, tell her in your thread with her so her copy matches.
- You own the notes, pages and widget. `drives.trips` is the phone's: never written from here.

## Tables
- `fills`: at (ms), station, gallons, price (a gallon), usd (total), odometer, card (name and at most 4 digits),
  source, note.
- mpg = miles between two fills with odometers / gallons of the later fill (ignore results outside 5 to 80).

## The Zeus import (his 6 fills)
Zeus's mini apps arrive as a zip in the server's `import/` folder. Zeus's fill-ups had `at, place, cents or usd,
gallons, price_per_gal, odometer, card, state`. Map them into `fills` once: place -> station, price_per_gal -> price,
cents / 100 -> usd, `source = 'zeus'`, only rows with state `logged` (skip open and skipped ones). Check for duplicates
by `at` first. Tell him when it's done ("6 fill-ups moved from Zeus").

## Extending
- `pages/index.html`: Fuel tab (mpg, cost a mile, miles since the last fill, the fills, an Add form) and Drives tab
  (this week's miles and the recent trips from `drives.trips`).
- `widgets/fuel.html`: last mpg and miles since the last fill.
- Lease pace, the car's milestones and places belong in the Drives app's notes (`drives/notes/car.md`), not here.
