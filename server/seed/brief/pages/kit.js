/* Small helpers for this app's pages. Reads come from the phone's copy of this app's tables (window.apollo.sql, one
   read-only SELECT). His taps that change rows go to the server as a page_write fact (insert/update/replace on this
   app's own tables, through the zeusNative bridge) and show here after the next sync, so pages update their own view
   right away. apollo.tap tells Athena he tapped something. */
var $ = function (id) { return document.getElementById(id); };
function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
/* A value as an SQLite literal, for reads (apollo.sql takes no parameters). */
function lit(v) { return v == null ? "NULL" : typeof v === "number" ? String(v) : "'" + String(v).replace(/'/g, "''") + "'"; }
function q(sql) {
  if (!window.apollo) return [];
  var r = window.apollo.sql(sql);
  return Array.isArray(r) ? r : [];
}
function write(sql, params) {
  if (!window.zeusNative) return { error: "not on the phone" };
  try { return JSON.parse(window.zeusNative.sql(sql, JSON.stringify(params || []))); } catch (e) { return { error: String(e) }; }
}
function tap(label, data) { if (window.apollo) window.apollo.tap(label, data); }
function day(ms) { return new Date(Number(ms)).toLocaleDateString([], { month: "short", day: "numeric" }); }
function when(ms) { var d = new Date(Number(ms)); return day(ms) + ", " + d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }); }
function today() { var d = new Date(); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); }
function tabs(onShow) {
  document.querySelectorAll(".tabs button").forEach(function (b) {
    b.onclick = function () {
      document.querySelectorAll(".tabs button").forEach(function (x) {
        x.classList.toggle("on", x === b);
        $(x.dataset.t).classList.toggle("hidden", x !== b);
      });
      if (onShow) onShow(b.dataset.t);
    };
  });
}
/* Markdown for text kept in a table: headings, lists, bold, italic, links (https only). Escaped first. */
function md(s) {
  var out = [], list = false;
  function inline(t) {
    return esc(t).replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>").replace(/(^|[^*\w])\*([^*]+)\*(?![*\w])/g, "$1<i>$2</i>")
      .replace(/\[([^\]]+)\]\((https:\/\/[^)\s]+)\)/g, '<a href="$2">$1</a>');
  }
  String(s || "").split("\n").forEach(function (line) {
    var h = line.match(/^(#{1,3})\s+(.*)/), li = line.match(/^\s*[-*]\s+(.*)/);
    if (!li && list) { out.push("</ul>"); list = false; }
    if (h) out.push("<h" + (h[1].length + 1) + ">" + inline(h[2]) + "</h" + (h[1].length + 1) + ">");
    else if (li) { if (!list) { out.push("<ul>"); list = true; } out.push("<li>" + inline(li[1]) + "</li>"); }
    else if (line.trim()) out.push("<p>" + inline(line) + "</p>");
  });
  if (list) out.push("</ul>");
  return out.join("");
}
