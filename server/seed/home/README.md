# Home (Hestia)

For Athena. docs/PLAN.md, Smart home. The phone has a small Google Home bridge (list, state, command) with a hard block:
locks, garage doors and gates, cameras, doorbells and security systems are never controlled by Apollo, not by his tap,
not by you, whatever the approvals say. Thermostats, speakers and TVs stay with the Google Home app.

## Who owns what
- `devices` is a **phone table**: each `home` fact (on every real change, and a snapshot every 15 minutes) replaces it.
  Read it for state and temperatures; never write it.
- `log` is shared: his taps on the page add a row (`who = 'owner'`), and you add one for every home action you take
  (`who = 'athena'`, with `before` as JSON so Undo can put it back, and `why`). You own the notes and pages.

## His taps
A tile tap on `pages/index.html` goes to the phone's bridge as `apollo.tap("home", {id, on})`: the phone carries it
out itself (his own tap: no approval, still through the hard block) and the call's promise says `{ok, detail}`; only a
done tap writes a `log` row. It also reaches you as a `page_tap` fact {app: home, label: home, data: {id, on}, ok,
detail}: already done (or refused), so don't carry it out again. A page never shows a control for a forbidden kind:
it lists only `controllable = 1` devices (and filters the forbidden kinds again by name).

## Your actions
`phone_do` with `lights`, `fan` or `plug` (`{device or room, on, brightness, kelvin}`): free with Undo. Log each one.

## Undo
Undo on a log row sends the tap that restores `before` (`apollo.tap("home", {id, on, undo: <log id>})`) and sets
`undone_at`. When he undoes one of yours, the phone asks "once or always?"; "always" adds an approvals rule, which is
his to make, not yours.

## Notes
`notes/home.md`: the rooms, what each device is for, his habits (fan on at night when it's warm, lights off when he
leaves). A cold night with the fan on is his request: read `temp_c` from `devices` and the overnight low from Weather.
