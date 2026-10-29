# Weather

Zeus's Aeolia, moved into a mini app (docs/PLAN.md, Weather). `pages/index.html` is the page with all its scripts, the
Samsung scenes (`pages/samsung/`, webp and character mp4s) and fonts. `pages/weather-bridge.js` stands in for Zeus's
native bridge: forecast (Open-Meteo), city search, NWS alerts, air quality and radar (IEM, RainViewer) go through
`apollo.fetch`, which only reaches hosts on Owner's allowlist. The page keeps its place and settings in its own
localStorage.

Yours, Athena: add code-drawn animations (CSS, SVG, canvas) and new widgets; the bundled video scenes stay as they are
(new binaries only come from Owner). `widgets/now.html` is the home-screen widget: today's temperature and sky from the
cached forecast. NWS alerts that matter reach you as facts; you decide what pings him.

## NWS alerts (the server's job)
Every 30 minutes the server asks api.weather.gov for the active alerts at each of his saved places (the drives app's
`places` table, US places only). Each new alert is a `weather_alert` fact: event, severity, urgency, certainty, headline,
area, onset, ends, description, instruction, place. Extreme and Severe ones come marked urgent. An update of an alert
you were already told is not sent again. To watch a new spot (his parents' town, a trip), add it to `drives.places`.
A routine row can trigger on `weather_alert` (or `weather_alert:severe`).

## Not in the public copy

The Samsung scene artwork (`pages/samsung/`, webp layers and character mp4s) and its catalog are not included: they
are Samsung's. `pages/samsung-catalog.js` is a stub with an empty catalog, so the page falls back to its own
illustrated scenes; the Samsung image tags in `index.html` show nothing.
