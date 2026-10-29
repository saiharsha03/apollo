/* Athena's app kit, offline: window.zeus (the only door to data), zchart (SVG charts) and zmap (his map, tiles from
   Zeus). No network: every request but Zeus's own is refused before it leaves the phone. */
(function () {
  "use strict";
  var N = window.zeusNative;
  function j(s) { try { return JSON.parse(s); } catch (e) { return { error: String(s) }; } }
  function css(name) { return getComputedStyle(document.documentElement).getPropertyValue("--" + name).trim(); }

  window.zeus = {
    /* One statement on this app's own tables. Selects give {columns, rows}; writes give {changed}. */
    sql: function (statement, args) { return j(N.sql(statement, JSON.stringify(args || []))); },
    /* A read_zeus area (never Calypso): zeus.read("drives", {days: 30}). */
    read: function (area, args) { return j(N.read(area, JSON.stringify(args || {}))); },
    file: function (path) { return N.file(path); },
    /* A Zeus place (ODYSSEY, MONEY...), another page of this app ("page:history"), or an app ("app:tv-shows"). */
    open: function (target) { N.open(target); },
    /* Tell Athena: in her next batch, or now when urgent. */
    tell: function (text, urgent) { N.tell(String(text), !!urgent); },
    haptic: function () { N.haptic(); },
    theme: function () { var t = j(N.theme()); t.color = css; return t; },
    screen: function () { return j(N.screen()); },
    now: function () { return j(N.now()); },
    /* Voice notes: Apollo records (the page has no mic), Sarvam writes the words, the audio stays in audio/. start(cb) only
       asks (he taps Record on Apollo's sheet); window.zeusVoiceState({recording}) says when the mic starts and ends. cb, or
       stop(cb)'s, gets {path, text, seconds, error} however it ends (his Cancel, Apollo's Stop or yours); retry(path, cb)
       for a note kept without words. */
    voice: {
      start: function (cb) { if (cb) window.zeusVoice = function (r) { window.zeusVoice = null; cb(r); }; return j(N.voiceStart()); },
      stop: function (cb) { window.zeusVoice = function (r) { window.zeusVoice = null; if (cb) cb(r); }; N.voiceStop(); },
      retry: function (path, cb) { window.zeusVoice = function (r) { window.zeusVoice = null; if (cb) cb(r); }; N.voiceRetry(path); },
      play: function (path) { return j(N.play(path)); },
      stopPlay: function () { N.stopPlay(); }
    },
    /* Another app of yours, read only: one SELECT on its tables, or one of its text files. */
    peek: function (app, statement, args) { return j(N.peek(app, statement, JSON.stringify(args || []))); },
    peekFile: function (app, path) { return N.peekFile(app, path); },
    /* A file of Athena's own workspace on his server, read only: zeus.workspace("tracker/bugs.md") gives the file's JSON (its text in it). */
    workspace: function (path) { return j(N.workspace(path)); },
    /* "Just for me": Zeus's own page, sealed into Calypso; nothing he writes there reaches this page or Athena. */
    privateNote: function () { N.privateNote(); },
    color: css
  };
  window.addEventListener("error", function (e) { console.error((e && e.message) || "error"); });

  function el(tag, attrs, parent) {
    var e = document.createElementNS("http://www.w3.org/2000/svg", tag);
    for (var k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }
  function target(t) { return typeof t === "string" ? document.querySelector(t) : t; }
  function fmt(v) { return Math.abs(v) >= 1000 ? (Math.round(v / 100) / 10) + "k" : (Math.round(v * 10) / 10).toString(); }
  /* The chart palette: saffron first, then quiet inks (near-monochrome). */
  function palette() { return [css("accent") || "#5B8CFF", css("ink2") || "#8B939C", css("ink3") || "#8B939C", css("accent-ink") || "#5B8CFF", css("rule") || "#262A30"]; }

  /* zchart(el, {type: line|area|bar|donut|spark|heat, labels: [...], series: [{name, values}], height, rows/cols for heat}). */
  window.zchart = function (t, o) {
    var box = target(t); if (!box) return;
    box.classList.add("zchart"); box.innerHTML = "";
    var W = box.clientWidth || 320, H = o.height || (o.type === "spark" ? 36 : 180), pal = palette();
    var svg = el("svg", { viewBox: "0 0 " + W + " " + H, width: W, height: H }, box);
    var ink3 = css("ink3") || "#8B939C", rule = css("rule") || "#262A30";
    var series = o.series || [{ values: o.values || [] }];
    if (o.type === "donut") {
      var vals = series[0].values, sum = vals.reduce(function (a, b) { return a + b; }, 0) || 1, r = Math.min(W, H) / 2 - 6, cx = W / 2, cy = H / 2, a0 = -Math.PI / 2;
      vals.forEach(function (v, i) {
        var a1 = a0 + 2 * Math.PI * v / sum, large = a1 - a0 > Math.PI ? 1 : 0;
        el("path", { d: "M" + (cx + r * Math.cos(a0)) + " " + (cy + r * Math.sin(a0)) + " A" + r + " " + r + " 0 " + large + " 1 " + (cx + r * Math.cos(a1)) + " " + (cy + r * Math.sin(a1)),
          fill: "none", stroke: pal[i % pal.length], "stroke-width": 18 }, svg);
        a0 = a1;
      });
      if (o.center) { var tx = el("text", { x: cx, y: cy + 8, "text-anchor": "middle", "font-size": 24, "font-weight": 600, fill: css("ink") }, svg); tx.textContent = o.center; }
      return svg;
    }
    if (o.type === "heat") {
      var rows = o.rows || series.length, cols = o.cols || (series[0].values || []).length, cw = W / cols, ch = H / rows, all = [];
      series.forEach(function (s) { all = all.concat(s.values); });
      var mx = Math.max.apply(null, all.concat([1]));
      series.forEach(function (s, ri) { s.values.forEach(function (v, ci) {
        el("rect", { x: ci * cw + 1, y: ri * ch + 1, width: cw - 2, height: ch - 2, rx: 3, fill: pal[0], "fill-opacity": (0.08 + 0.92 * v / mx).toFixed(2) }, svg);
      }); });
      return svg;
    }
    var pad = o.type === "spark" ? 2 : 24, left = o.type === "spark" ? 2 : 34, all2 = [];
    series.forEach(function (s) { all2 = all2.concat(s.values); });
    var max = Math.max.apply(null, all2.concat([0])), min = Math.min.apply(null, all2.concat([0])), span = (max - min) || 1;
    var n = Math.max.apply(null, series.map(function (s) { return s.values.length; })), iw = W - left - 4, ih = H - pad * (o.type === "spark" ? 1 : 1.6);
    function x(i) { return left + (n <= 1 ? iw / 2 : i * iw / (n - 1)); }
    function y(v) { return pad / 2 + ih - (v - min) / span * ih; }
    if (o.type !== "spark") {
      [min, (min + max) / 2, max].forEach(function (v) {
        el("line", { x1: left, x2: W, y1: y(v), y2: y(v), stroke: rule, "stroke-width": 1 }, svg);
        var t2 = el("text", { x: left - 6, y: y(v) + 4, "text-anchor": "end", "font-size": 11, fill: ink3 }, svg); t2.textContent = fmt(v);
      });
      (o.labels || []).forEach(function (l, i) {
        if (n > 8 && i % Math.ceil(n / 6)) return;
        var t3 = el("text", { x: o.type === "bar" ? left + (i + 0.5) * iw / n : x(i), y: H - 4, "text-anchor": "middle", "font-size": 11, fill: ink3 }, svg); t3.textContent = l;
      });
    }
    series.forEach(function (s, si) {
      var col = s.color || pal[si % pal.length];
      if (o.type === "bar") {
        var bw = iw / n / series.length * 0.7;
        s.values.forEach(function (v, i) { el("rect", { x: left + i * iw / n + (iw / n - bw * series.length) / 2 + si * bw, y: y(Math.max(v, 0)), width: bw, height: Math.abs(y(v) - y(0)), rx: 3, fill: col }, svg); });
        return;
      }
      var d = s.values.map(function (v, i) { return (i ? "L" : "M") + x(i).toFixed(1) + " " + y(v).toFixed(1); }).join(" ");
      if (o.type === "area") el("path", { d: d + " L" + x(s.values.length - 1) + " " + y(min) + " L" + x(0) + " " + y(min) + " Z", fill: col, "fill-opacity": 0.15 }, svg);
      el("path", { d: d, fill: "none", stroke: col, "stroke-width": o.type === "spark" ? 1.5 : 2, "stroke-linejoin": "round", "stroke-linecap": "round" }, svg);
    });
    return svg;
  };

  /* zmap(el, {center: [lat, lng], zoom, height, pins: [{lat, lng, label}], lines: [[[lat, lng], ...]], areas: [{lat, lng, radius_m}]}). */
  window.zmap = function (t, o) {
    var box = target(t); if (!box) return;
    box.classList.add("zmap"); box.innerHTML = "";
    var H = o.height || 260; box.style.height = H + "px";
    var dark = zeus.theme().dark ? "dark" : "light", z = Math.max(3, Math.min(16, o.zoom || 12)), c = o.center || (o.pins && o.pins[0] ? [o.pins[0].lat, o.pins[0].lng] : [42.386, -71.078]);
    var tiles = document.createElement("div"), svg = el("svg", {}), off = { x: 0, y: 0 }, accent = css("accent") || "#5B8CFF", ink = css("ink") || "#ECEEF1";
    box.appendChild(tiles); box.appendChild(svg);
    function px(lat, lng, zz) { var s = 256 * Math.pow(2, zz), r = lat * Math.PI / 180; return { x: (lng + 180) / 360 * s, y: (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * s }; }
    function draw() {
      var W = box.clientWidth || 320, ctr = px(c[0], c[1], z), x0 = ctr.x - W / 2 + off.x, y0 = ctr.y - H / 2 + off.y;
      tiles.innerHTML = "";
      for (var tx = Math.floor(x0 / 256); tx <= Math.floor((x0 + W) / 256); tx++) for (var ty = Math.floor(y0 / 256); ty <= Math.floor((y0 + H) / 256); ty++) {
        if (ty < 0 || ty >= Math.pow(2, z)) continue;
        var img = document.createElement("img"); img.src = "/tile/" + dark + "/" + z + "/" + tx + "/" + ty; img.style.left = (tx * 256 - x0) + "px"; img.style.top = (ty * 256 - y0) + "px"; img.alt = "";
        tiles.appendChild(img);
      }
      svg.innerHTML = ""; svg.setAttribute("width", W); svg.setAttribute("height", H);
      function p(lat, lng) { var q = px(lat, lng, z); return [q.x - x0, q.y - y0]; }
      (o.areas || []).forEach(function (a) { var q = p(a.lat, a.lng), mpp = 156543.03 * Math.cos(a.lat * Math.PI / 180) / Math.pow(2, z);
        el("circle", { cx: q[0], cy: q[1], r: (a.radius_m || 100) / mpp, fill: accent, "fill-opacity": 0.18, stroke: accent, "stroke-width": 1 }, svg); });
      (o.lines || []).forEach(function (l) { el("path", { d: l.map(function (pt, i) { var q = p(pt[0], pt[1]); return (i ? "L" : "M") + q[0] + " " + q[1]; }).join(" "), fill: "none", stroke: accent, "stroke-width": 3, "stroke-linecap": "round", "stroke-linejoin": "round" }, svg); });
      (o.pins || []).forEach(function (pn) { var q = p(pn.lat, pn.lng);
        el("circle", { cx: q[0], cy: q[1], r: pn.size || 7, fill: pn.color || accent, stroke: css("surface") || "#171A1E", "stroke-width": 2 }, svg);
        if (pn.label) { var tl = el("text", { x: q[0] + 10, y: q[1] + 4, "font-size": 12, "font-weight": 600, fill: ink, "paint-order": "stroke", stroke: css("surface") || "#171A1E", "stroke-width": 3 }, svg); tl.textContent = pn.label; } });
    }
    var drag = null;
    box.addEventListener("pointerdown", function (e) { drag = { x: e.clientX, y: e.clientY, ox: off.x, oy: off.y }; box.setPointerCapture(e.pointerId); });
    box.addEventListener("pointermove", function (e) { if (!drag) return; off.x = drag.ox - (e.clientX - drag.x); off.y = drag.oy - (e.clientY - drag.y); draw(); });
    box.addEventListener("pointerup", function () { drag = null; });
    var zb = document.createElement("div"); zb.className = "zoom";
    [["+", 1], ["−", -1]].forEach(function (b) { var btn = document.createElement("button"); btn.textContent = b[0];
      btn.addEventListener("pointerdown", function (e) { e.stopPropagation(); });
      btn.addEventListener("click", function (e) { e.stopPropagation(); var W = box.clientWidth || 320, ctr = px(c[0], c[1], z);
        var ll = (function (x, y, zz) { var s = 256 * Math.pow(2, zz), n = Math.PI - 2 * Math.PI * y / s; return [180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))), x / s * 360 - 180]; })(ctr.x + off.x, ctr.y + off.y, z);
        c = ll; off = { x: 0, y: 0 }; z = Math.max(3, Math.min(16, z + b[1])); draw(); });
      zb.appendChild(btn); });
    box.appendChild(zb);
    draw();
    return { redraw: draw };
  };
})();
