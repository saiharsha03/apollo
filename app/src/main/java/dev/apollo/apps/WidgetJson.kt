package dev.apollo.apps

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Zeus's widgets/<w>.json format (rows of stat, text, countdown, clock, progress, lines, check, rotate, picture, and up to
 * four buttons), drawn as HTML so the one widget renders it like any other page. Values come from the app's mirrored
 * data.db. Buttons are data-tap zones "zb:<i>"; the widget turns a tap into the button's insert or tell.
 */
object WidgetJson {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun html(spec: JSONObject, db: File, appTitle: String): String {
        val body = StringBuilder()
        body.append("<div class=t>").append(esc(spec.optString("title").ifBlank { appTitle })).append("</div>")
        val rows = spec.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val sql = r.optString("sql").ifBlank { null }
            when (r.optString("type")) {
                "stat" -> {
                    body.append("<div class=s>").append(esc(ZeusSql.one(db, sql) ?: r.optString("value"))).append("</div>")
                    r.optString("text").takeIf { it.isNotBlank() }?.let { body.append("<div class=m>").append(esc(it)).append("</div>") }
                }
                "text" -> body.append("<div class=m>").append(esc(ZeusSql.one(db, sql) ?: r.optString("text"))).append("</div>")
                "countdown" -> left(ZeusSql.one(db, sql) ?: r.optString("to"))?.let { body.append("<div class=s>").append(esc(it)).append("</div>") }
                "clock" -> {
                    val f = DateTimeFormatter.ofPattern("h:mm a")
                    body.append("<div class=m>Boston ").append(ZonedDateTime.now(ZoneId.of("America/New_York")).format(f))
                        .append(" · India ").append(ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).format(f)).append("</div>")
                }
                "progress" -> {
                    val v = (ZeusSql.one(db, sql) ?: r.optString("value")).toDoubleOrNull() ?: 0.0
                    val max = r.optString("max").toDoubleOrNull() ?: 100.0
                    body.append("<div class=p><i style=\"width:").append(((v / max).coerceIn(0.0, 1.0) * 100).toInt()).append("%\"></i></div>")
                }
                "lines", "rotate", "check" -> {
                    val items = r.optJSONArray("items")?.let { a -> List(a.length()) { listOf(a.optString(it)) } } ?: ZeusSql.lines(db, sql)
                    val shown = items.take(if (r.optString("type") == "rotate") 1 else 6).map { row ->
                        if (r.optString("type") == "check") (if (row.getOrNull(1)?.lowercase() in setOf("1", "done", "yes", "true", "x")) "✓ " else "○ ") + row.firstOrNull().orEmpty()
                        else row.filter { it.isNotBlank() }.joinToString(" · ")
                    }
                    if (shown.isEmpty()) body.append("<div class=m>").append(esc(r.optString("empty", "Nothing yet."))).append("</div>")
                    else shown.forEach { body.append("<div class=l>").append(esc(it)).append("</div>") }
                }
            }
        }
        val buttons = spec.optJSONArray("buttons") ?: JSONArray()
        if (buttons.length() > 0) {
            body.append("<div class=b>")
            for (i in 0 until minOf(4, buttons.length())) body.append("<span data-tap=\"zb:").append(i).append("\">").append(esc(buttons.optJSONObject(i)?.optString("label").orEmpty().take(16))).append("</span>")
            body.append("</div>")
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
            html,body{margin:0;height:100%;background:transparent}body{box-sizing:border-box;padding:14px 16px;background:#26231F;border-radius:22px;color:#F2EDE4;
            font:14px/1.35 Roboto,system-ui,sans-serif;display:flex;flex-direction:column;gap:4px;overflow:hidden}.t{font-weight:600;color:#E8A33D}
            .s{font-size:30px;font-weight:600}.m{color:#A39A8C}.l{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            .p{height:8px;border-radius:4px;background:#3a352f;overflow:hidden}.p i{display:block;height:100%;background:#E8A33D}
            .b{margin-top:auto;display:flex;gap:8px}.b span{flex:1;text-align:center;padding:8px 4px;border-radius:12px;background:#3a352f}
            </style></head><body>$body</body></html>"""
    }

    /** "3d 4h", "2h 05m" or "now" until a local or offset time. */
    fun left(to: String?, now: LocalDateTime = LocalDateTime.now()): String? {
        if (to.isNullOrBlank()) return null
        val t = runCatching { LocalDateTime.parse(to.take(16)) }.recoverCatching { OffsetDateTime.parse(to).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull() ?: return null
        val d = Duration.between(now, t)
        if (d.isNegative) return "now"
        return if (d.toDays() > 0) "${d.toDays()}d ${d.toHoursPart()}h" else "${d.toHours()}h ${"%02d".format(d.toMinutesPart())}m"
    }
}
