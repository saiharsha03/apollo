/* apollomap: a dark street map on any page, in one call. Tiles are TomTom's night map through Apollo's server
   (/tile/{z}/{x}/{y}.png; the key never reaches the page). Leaflet 1.9.4 (BSD-2-Clause, kit/leaflet-LICENSE.txt) is
   loaded from the kit on first use; no network.

   <script src="/kit/apollomap.js"></script>
   apollomap.show(el, {route: [[lat, lng], ...], markers: [{lat, lng, color, label}], fit: true}).then(function (map) {});

   Options: route (one line), routes ([{points, color, weight}] for several), markers, fit (default true: route and
   markers in view), center [lat, lng] + zoom (when not fitting), height (px, when el has none), still (true: no pan or
   zoom, for small previews). Colors take a CSS color or var name ("--alarm"). Resolves with the Leaflet map. */
(function () {
  "use strict";
  var loading = null;

  function load() {
    if (window.L) return Promise.resolve(window.L);
    if (loading) return loading;
    var css = document.createElement("link");
    css.rel = "stylesheet"; css.href = "/kit/leaflet.css";
    document.head.appendChild(css);
    var st = document.createElement("style");
    st.textContent =
      ".apollomap { background: var(--ground, #0E1013); border-radius: var(--radius, 20px); overflow: hidden; }" +
      ".apollomap .leaflet-bar { border: 1px solid var(--line, #262A30); border-radius: var(--radius-s, 12px); overflow: hidden; box-shadow: none; }" +
      ".apollomap .leaflet-bar a { background: var(--raised, #171A1E); color: var(--ink, #ECEEF1); border-bottom-color: var(--line, #262A30); width: 34px; height: 34px; line-height: 34px; }" +
      ".apollomap .leaflet-bar a.leaflet-disabled { color: var(--quiet, #8B939C); background: var(--raised, #171A1E); }" +
      ".apollomap .leaflet-control-attribution { background: transparent; color: var(--quiet, #8B939C); font-size: 10px; }" +
      ".apollomap .leaflet-tooltip { background: var(--raised, #171A1E); color: var(--ink, #ECEEF1); border: 1px solid var(--line, #262A30); border-radius: 8px; box-shadow: none; font-weight: 600; padding: 3px 7px; }" +
      ".apollomap .leaflet-tooltip:before { display: none; }";
    document.head.appendChild(st);
    loading = new Promise(function (ok, no) {
      var s = document.createElement("script");
      s.src = "/kit/leaflet.js";
      s.onload = function () { ok(window.L); };
      s.onerror = function () { loading = null; no(new Error("leaflet did not load")); };
      document.head.appendChild(s);
    });
    return loading;
  }

  function color(c, fallback) {
    var v = c || fallback;
    if (v.indexOf("--") === 0) v = getComputedStyle(document.documentElement).getPropertyValue(v).trim() || null;
    return v || "#ECEEF1";
  }

  function show(el, o) {
    o = o || {};
    var box = typeof el === "string" ? document.querySelector(el) : el;
    if (!box) return Promise.reject(new Error("no element"));
    return load().then(function (L) {
      if (box._apollomap) box._apollomap.remove();
      if (!box.clientHeight) box.style.height = (o.height || 260) + "px";
      box.classList.add("apollomap");
      var map = L.map(box, { zoomControl: !o.still, dragging: !o.still, touchZoom: !o.still, scrollWheelZoom: false,
        doubleClickZoom: !o.still, boxZoom: false, keyboard: false, attributionControl: true });
      box._apollomap = map;
      map.attributionControl.setPrefix("");
      L.tileLayer("/tile/{z}/{x}/{y}.png", { maxZoom: 18, minZoom: 2, tileSize: 256, attribution: "© TomTom" }).addTo(map);
      var all = [];
      var lines = (o.routes || []).slice();
      if (o.route) lines.unshift({ points: o.route });
      lines.forEach(function (r) {
        var pts = (r.points || []).filter(function (p) { return p && isFinite(p[0]) && isFinite(p[1]); }).map(function (p) { return [p[0], p[1]]; });
        if (pts.length < 2) return;
        L.polyline(pts, { color: color(r.color, "--accent"), weight: r.weight || 4, opacity: 0.95, lineCap: "round", lineJoin: "round", interactive: false }).addTo(map);
        all = all.concat(pts);
      });
      (o.markers || []).forEach(function (m) {
        if (!isFinite(m.lat) || !isFinite(m.lng)) return;
        var c = L.circleMarker([m.lat, m.lng], { radius: m.size || 6, color: color(null, "--ground"), weight: 2,
          fillColor: color(m.color, "--ink"), fillOpacity: 1, interactive: !!m.label }).addTo(map);
        if (m.label) {
          // An element with text, never a string: Leaflet takes a string as HTML, and labels can be other people's words.
          var tip = document.createElement("span");
          tip.textContent = String(m.label);
          c.bindTooltip(tip, { permanent: true, direction: "right", offset: [8, 0] });
        }
        all.push([m.lat, m.lng]);
      });
      if (o.fit !== false && all.length > 1) map.fitBounds(all, { padding: o.still ? [6, 6] : [24, 24], maxZoom: 17 });
      else if (o.center) map.setView(o.center, o.zoom || 14);
      else if (all.length) map.setView(all[0], o.zoom || 15);
      else map.setView([42.386, -71.078], o.zoom || 11);
      return map;
    });
  }

  window.apollomap = { show: show, load: load };
})();
