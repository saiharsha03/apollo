package dev.apollo.pixel

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.widget.RemoteViews
import dev.apollo.R
import dev.apollo.apps.Widgets

/**
 * The one widget's "agent character" what: the current pose as a still picture with the tool fact under it. A
 * home-screen widget can't animate (RemoteViews), so it is redrawn on each activity (PixelFeed) and by the widgets'
 * periodic refresh (stale labels), showing the scene's first frame.
 */
object PixelWidget {
    const val PREFIX = "agent:"
    val choices = listOf("athena" to "Athena (owl): what she is doing", "iris" to "Iris (cloud): what she is doing", "atlas" to "Atlas (titan): what he is doing")

    fun agentOf(choice: String?): String? = choice?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it in Activity.AGENTS }

    fun redraw(c: Context, agent: String) {
        for (id in Widgets.ids(c)) if (agentOf(Widgets.choice(c, id)) == agent) draw(c, id, agent)
    }

    fun draw(c: Context, id: Int, agent: String) {
        val awm = AppWidgetManager.getInstance(c)
        val o = awm.getAppWidgetOptions(id)
        val d = c.resources.displayMetrics.density
        val w = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110) * d).toInt().coerceIn(48, 1400)
        val h = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110) * d).toInt().coerceIn(48, 1400)
        val now = System.currentTimeMillis()
        val shown = PixelEngine.show(PixelFeed.latest(c, agent), now, PixelEngine.hour(now))
        val views = RemoteViews(c.packageName, R.layout.apollo_widget)
        views.setImageViewBitmap(R.id.widget_image, bitmap(c, agent, shown, w, h, d))
        c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, id * 16, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        awm.updateAppWidget(id, views)
    }

    /** The card: rounded ground, the character's dot matrix centred, the label under it. */
    fun bitmap(c: Context, agent: String, shown: Shown, w: Int, h: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF1C1A17.toInt()
        cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22 * density, 22 * density, p)
        val text = 13 * density
        val labelH = if (shown.label.isEmpty()) 0f else text * 1.8f
        val ch = PixelArt.builtin(c, agent)
        val gw = ch?.w ?: 24; val gh = ch?.h ?: 20
        val dot = minOf((w - 24 * density) / gw, (h - 24 * density - labelH) / gh).coerceAtLeast(1f)
        val x0 = (w - dot * gw) / 2
        val y0 = (h - dot * gh - labelH) / 2
        val gap = dot * PixelEngine.gap(true)
        val grid = ch?.let { PixelEngine.frame(it, shown.scene, 0) } ?: IntArray(gw * gh)
        for (k in grid.indices) {
            val v = grid[k]
            p.color = if (v == 0) PixelEngine.OFF else v
            val x = x0 + (k % gw) * dot; val y = y0 + (k / gw) * dot
            cv.drawRoundRect(RectF(x + gap, y + gap, x + dot - gap, y + dot - gap), dot * 0.3f, dot * 0.3f, p)
        }
        if (labelH > 0) {
            val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA39A8C.toInt(); textSize = text; typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER }
            val s = TextUtils.ellipsize(shown.label, tp, w - 24 * density, TextUtils.TruncateAt.END).toString()
            cv.drawText(s, w / 2f, y0 + dot * gh + text * 1.4f, tp)
        }
        return bmp
    }
}
