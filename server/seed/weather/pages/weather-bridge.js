/* Aeolia's native bridge (window.Weather in Zeus), rebuilt on Apollo's engine: the same calls and the same reply
   functions, with the network going through apollo.fetch (only the weather hosts on Owner's allowlist) and what Zeus
   kept in its prefs kept in this page's localStorage. Zeus-only extras (Nano quips, wake alarms, pinning a widget)
   answer quietly so the page falls back to its own text. */
(function () {
  "use strict";
  var LS = window.localStorage;
  function get(k) { try { return LS.getItem("aeolia." + k); } catch (e) { return null; } }
  function set(k, v) { try { LS.setItem("aeolia." + k, v); } catch (e) {} }
  function reply(fn, v) { var f = window[fn]; if (typeof f === "function") { try { f(v); } catch (e) { console.error(fn, e); } } }
  function json(url) { return window.apollo.fetch(url).then(JSON.parse); }
  var generation = 0;

  var FORECAST = "https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}" +
    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,surface_pressure,dew_point_2m,visibility,precipitation,uv_index" +
    "&minutely_15=precipitation&forecast_minutely_15=8&past_hours=24" +
    "&hourly=is_day,temperature_2m,precipitation_probability,precipitation,rain,showers,weather_code,snowfall,visibility,wind_gusts_10m,apparent_temperature,uv_index" +
    "&daily=temperature_2m_max,temperature_2m_min,weather_code,sunrise,sunset,uv_index_max,snowfall_sum,rain_sum,showers_sum,precipitation_sum,precipitation_probability_max" +
    "&timezone=auto&forecast_days=10";
  function fill(t, lat, lon) { return t.replace("{lat}", lat).replace("{lon}", lon); }
  function ok(lat, lon) { return isFinite(lat) && isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180; }

  window.Weather = {
    saved: function () { return get("saved") || "null"; },
    cached: function () { return get("forecast") || "null"; },
    cachedAlerts: function () { return get("alerts") || "null"; },
    getPreferences: function () { return get("prefs") || "null"; },
    savePreferences: function (v) { if (v && v.length <= 65536) set("prefs", v); },
    catalog: function (v) { if (v && v.length <= 65536) set("catalog", v); },
    openSettings: function () {},
    radar: function () {
      var out = { iem: {} }, any = false;
      Promise.all([
        json("https://mesonet.agron.iastate.edu/data/gis/images/4326/USCOMP/n0q_0.json").then(function (d) { out.iem.n0q = d; any = true; }).catch(function () {}),
        json("https://mesonet.agron.iastate.edu/data/gis/images/4326/hrrr/refd_0000.json").then(function (d) { out.iem.hrrr = d; }).catch(function () {}),
        json("https://api.rainviewer.com/public/weather-maps.json").then(function (d) { out.rainviewer = d; any = true; }).catch(function () {})
      ]).then(function () { if (any) reply("radarReady", out); else reply("radarUnavailable", null); });
    },
    search: function (q) {
      q = String(q || "").trim().slice(0, 100);
      if (q.length < 2) return;
      json("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name=" + encodeURIComponent(q)).then(function (d) {
        var out = (d.results || []).filter(function (r) { return ok(r.latitude, r.longitude) && r.name; }).map(function (r) {
          return { name: String(r.name).slice(0, 80), admin1: String(r.admin1 || "").slice(0, 80), country: String(r.country || "").slice(0, 80),
            country_code: String(r.country_code || "").slice(0, 3), latitude: r.latitude, longitude: r.longitude };
        });
        reply("searchResults", { results: out });
      }).catch(function () { reply("showError", "Couldn't search cities. Check your connection and retry."); });
    },
    forecast: function (lat, lon, name, country) {
      if (!ok(lat, lon)) return;
      var g = ++generation;
      set("saved", JSON.stringify({ lat: lat, lon: lon, name: String(name || "").slice(0, 80), country: String(country || "").replace(/[^A-Za-z]/g, "").slice(0, 3) }));
      window.apollo.fetch(fill(FORECAST, lat, lon)).then(function (text) {
        if (g !== generation) return;   // a newer place was chosen while this one downloaded
        var d = JSON.parse(text); d.selected_lat = lat; d.selected_lon = lon;
        set("forecast", JSON.stringify(d));
        reply("renderWeather", d);
      }).catch(function () { if (g === generation) reply("showError", "Couldn't update weather. Check your connection, then tap refresh."); });
    },
    details: function (lat, lon, country) {
      if (!ok(lat, lon)) return;
      // NWS covers the US; elsewhere the page says alerts aren't available, rather than "all clear".
      var alerts = String(country || "").toUpperCase() === "IN"
        ? Promise.resolve({ unsupported: true, features: [] })
        : json("https://api.weather.gov/alerts/active?point=" + lat + "," + lon);
      alerts.then(function (a) {
        a.selected_lat = lat; a.selected_lon = lon; a.fetched_at = Date.now();
        set("alerts", JSON.stringify(a));
        reply("renderAlerts", a);
      }).catch(function () {
        reply("alertsUnavailable", { selected_lat: lat, selected_lon: lon, message: "NWS alerts could not be checked. Try refresh; check weather.gov for official warnings." });
      });
      json("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat + "&longitude=" + lon + "&current=us_aqi,pm2_5,pm10&timezone=auto")
        .then(function (a) { a.selected_lat = lat; a.selected_lon = lon; reply("renderAir", a); })
        .catch(function () { reply("airUnavailable", null); });
    },
    holidays: function () { reply("holidaysUnavailable", null); },
    notifications: function () { return false; },
    setNotifications: function (on) { reply("notificationsChanged", false); },
    places: function () { return "null"; },
    hereAllowed: function () { return true; },
    locate: function () {
      var l = window.apollo.location();
      if (!l) { reply("locationUnavailable", null); reply("showError", "Location unavailable. Search for your city instead."); return; }
      reply("deviceLocation", { lat: l.lat, lon: l.lon, name: "Current location" });
    },
    openOfficial: function (url) { if (/^https:\/\//.test(String(url))) window.apollo.open(String(url)); },
    haptic: function () {},
    quip: function () {},
    saveQuip: function () {},
    nanoStatus: function () {},
    pinWidget: function () {},
    openExactAlarmSettings: function () {},
    setWakeAlarm: function () {},
    setWakeLine: function () {},
    wakeAlarm: function () { return "null"; }
  };
})();
