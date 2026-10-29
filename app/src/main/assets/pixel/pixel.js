// apollo.pixel: Apollo's pixel characters on a mini-app page (docs/PIXEL.md). One file, no dependencies, a canvas.
// Served after the apollo shim in _apollo.js. A bad character file or scene fails soft: it is dropped, never thrown.
(function () {
  if (!window.apollo || window.apollo.pixel) return;
  var MAX_BYTES = 262144, MAX_SIDE = 64, MAX_SCENES = 40, MAX_FRAMES = 32, MAX_PALETTE = 16;
  var MAX_STAGE = 200, MAX_ACTORS = 12;
  var BUILTIN = { owl: 1, cloud: 1, titan: 1 };
  var AGENT = { athena: 'owl', iris: 'cloud', atlas: 'titan' };
  var OFF = '#2E2A25', GROUND = '#1C1A17';
  var cache = {};

  function warn(what) { try { console.warn('apollo.pixel: ' + what); } catch (e) {} }
  var RED = '#D71921';   // the palette's "red": Apollo's one red (the phone's Theme token)
  function color(v) { return v === 'red' || (typeof v === 'string' && /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(v)); }

  /** A character file's JSON to a character, or null. Same rules as the phone's PixelArt.parse. */
  function parse(j) {
    if (!j || typeof j !== 'object') return null;
    var w = j.w | 0, h = j.h | 0;
    if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) { warn('w and h must be 1..' + MAX_SIDE); return null; }
    var pal = {}, n = 0, p = j.palette || {};
    Object.keys(p).forEach(function (k) {
      if (k.length === 1 && k !== '.' && color(p[k]) && n < MAX_PALETTE) { pal[k] = p[k] === 'red' ? RED : p[k]; n++; }
    });
    var scenes = {}, count = 0, sc = j.scenes || {};
    Object.keys(sc).forEach(function (name) {
      if (count >= MAX_SCENES || !/^[a-z0-9_-]{1,40}$/.test(name)) return;
      var s = sc[name], fr = s && s.frames;
      var ok = Array.isArray(fr) && fr.length >= 1 && fr.length <= MAX_FRAMES && fr.every(function (f) {
        return Array.isArray(f) && f.length === h && f.every(function (r) {
          if (typeof r !== 'string' || r.length !== w) return false;
          for (var i = 0; i < r.length; i++) if (r[i] !== '.' && !pal[r[i]]) return false;
          return true;
        });
      });
      if (!ok) { warn('scene "' + name + '" dropped: 1..' + MAX_FRAMES + ' frames of ' + h + ' rows x ' + w + ' palette chars'); return; }
      scenes[name] = { ms: Math.min(10000, Math.max(40, +s.ms || 200)), frames: fr };
      count++;
    });
    if (!count) { warn('no usable scene'); return null; }
    return { name: String(j.name || ''), w: w, h: h, palette: pal, scenes: scenes };
  }

  function get(url) {
    return fetch(url).then(function (r) { return r.ok ? r.text() : null; })
      .then(function (t) { return t && t.length <= MAX_BYTES ? parse(JSON.parse(t)) : null; })
      .catch(function () { return null; });
  }

  /** A character by name: the app's own pixel/<name>.json first, else a built-in (owl, cloud, titan, or an agent's name). */
  function load(name) {
    if (name && typeof name === 'object') return Promise.resolve(parse(name));
    name = AGENT[name] || name;
    if (typeof name !== 'string' || !/^[a-z0-9-]{1,40}$/.test(name)) return Promise.resolve(null);
    if (!cache[name]) {
      var app = window.apollo.app;
      var own = app ? get(location.origin + '/' + app + '/pixel/' + name + '.json') : Promise.resolve(null);
      cache[name] = own.then(function (c) { return c || (BUILTIN[name] ? get(location.origin + '/_pixel/' + name + '.json') : null); });
    }
    return cache[name];
  }

  function sceneOf(ch, name) { return ch.scenes[name] || ch.scenes.working || ch.scenes[Object.keys(ch.scenes)[0]]; }

  function dotAt(ctx, x, y, s, r) {
    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(x, y, s, s, r); else ctx.rect(x, y, s, s);
    ctx.fill();
  }

  /** One frame of a character at dot (x, y) on a grid of dot-sized cells. */
  function paint(ctx, ch, scene, i, x, y, dot, gap, flip) {
    var s = sceneOf(ch, scene), f = s.frames[((i % s.frames.length) + s.frames.length) % s.frames.length];
    var g = dot * gap, r = dot * 0.3;
    for (var j = 0; j < ch.h; j++) for (var k = 0; k < ch.w; k++) {
      var c = f[j][flip ? ch.w - 1 - k : k];
      if (c === '.') continue;
      ctx.fillStyle = ch.palette[c];
      dotAt(ctx, (x + k) * dot + g, (y + j) * dot + g, dot - 2 * g, r);
    }
  }

  function grid(ctx, w, h, dot, gap, background) {
    if (background) { ctx.fillStyle = background; ctx.fillRect(0, 0, w * dot, h * dot); }
    ctx.fillStyle = OFF;
    for (var j = 0; j < h; j++) for (var k = 0; k < w; k++) dotAt(ctx, k * dot + dot * gap, j * dot + dot * gap, dot * (1 - 2 * gap), dot * 0.3);
  }

  /** One still: draw(canvas, {character, scene, frame | time, x, y, scale, flip}). Adds to the canvas; never clears it. */
  function draw(canvas, o) {
    o = o || {};
    return load(o.character).then(function (ch) {
      if (!ch || !canvas || !canvas.getContext) return false;
      var dot = o.scale || Math.max(1, Math.floor(Math.min(canvas.width / ch.w, canvas.height / ch.h)));
      var s = sceneOf(ch, o.scene);
      var i = o.frame != null ? o.frame | 0 : Math.floor((o.time != null ? +o.time : Date.now()) / s.ms);
      paint(canvas.getContext('2d'), ch, o.scene, i, o.x | 0, o.y | 0, dot, o.matrix === false ? 0.06 : 0.14, !!o.flip);
      return true;
    });
  }

  /** Where an actor is at t ms: its start, then each move {x, y, ms} in turn; loops unless loop is false. */
  function place(a, t) {
    var moves = Array.isArray(a.moves) ? a.moves.filter(function (m) { return m && +m.ms > 0; }) : [];
    var x = +a.x || 0, y = +a.y || 0;
    var total = moves.reduce(function (n, m) { return n + +m.ms; }, 0);
    if (!total) return [Math.round(x), Math.round(y)];
    t = a.loop === false ? Math.min(t, total) : t % total;
    for (var i = 0; i < moves.length; i++) {
      var m = moves[i], mx = m.x != null ? +m.x : x, my = m.y != null ? +m.y : y;
      if (t <= +m.ms) { var k = t / +m.ms; return [Math.round(x + (mx - x) * k), Math.round(y + (my - y) * k)]; }
      t -= +m.ms; x = mx; y = my;
    }
    return [Math.round(x), Math.round(y)];
  }

  /**
   * A stage: several actors on one dot grid, animated until stop(). stage(canvas, {w, h, dot, matrix, background,
   * time, actors: [{character, scene, x, y, flip, moves: [{x, y, ms}], loop, delay}]}). Later actors draw on top.
   * Returns {ready, actors, stop, render(t)}; change an actor's scene, x or y any time and the next frame shows it.
   */
  function stage(canvas, o) {
    o = o || {};
    var w = Math.min(MAX_STAGE, Math.max(1, o.w | 0 || 40)), h = Math.min(MAX_STAGE, Math.max(1, o.h | 0 || 24));
    var actors = (Array.isArray(o.actors) ? o.actors : []).slice(0, MAX_ACTORS);
    var gap = o.matrix === false ? 0.06 : 0.14;
    var background = o.background === undefined ? GROUND : o.background;
    var chars = [], stopped = false, start = null, last = -1;
    var ctx = canvas && canvas.getContext ? canvas.getContext('2d') : null;
    var dot = o.dot || (canvas ? Math.max(1, Math.floor(Math.min(canvas.width / w, canvas.height / h))) : 1);
    var back = null;

    function render(t) {
      if (!ctx) return;
      if (!back) {
        back = document.createElement('canvas'); back.width = w * dot; back.height = h * dot;
        var b = back.getContext('2d');
        if (o.matrix === false) { if (background) { b.fillStyle = background; b.fillRect(0, 0, back.width, back.height); } }
        else grid(b, w, h, dot, gap, background);
      }
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(back, 0, 0);
      actors.forEach(function (a, n) {
        var ch = chars[n];
        if (!ch) return;
        var at = Math.max(0, t - (+a.delay || 0)), p = place(a, at), s = sceneOf(ch, a.scene);
        paint(ctx, ch, a.scene, Math.floor(at / s.ms), p[0], p[1], dot, gap, !!a.flip);
      });
    }

    function tick(now) {
      if (stopped) return;
      if (start == null) start = now;
      // 20 redraws a second at most: frames are 40ms or longer, and moves are whole dots.
      if (now - last >= 50) { last = now; render(now - start); }
      requestAnimationFrame(tick);
    }

    var ready = Promise.all(actors.map(function (a) { return load(a.character); })).then(function (list) {
      chars = list;
      list.forEach(function (c, n) { if (!c) warn('actor ' + n + ': no character "' + actors[n].character + '"'); });
      if (o.time != null) render(+o.time); else if (!stopped) requestAnimationFrame(tick);
      return list.filter(Boolean).length;
    });
    return { ready: ready, actors: actors, render: render, stop: function () { stopped = true; } };
  }

  window.apollo.pixel = { load: load, parse: parse, draw: draw, stage: stage, place: place };
})();
