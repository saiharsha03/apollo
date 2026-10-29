package dev.apollo.home

import android.content.Context

/** One thing pinned on Home: a mini app (a tile that opens it) or one of its widgets (a live panel, [widget] = its path). */
data class Pin(val app: String, val widget: String? = null) {
    val wire get() = if (widget == null) "app:$app" else "widget:$app/$widget"
}

/**
 * His pins on Home (PLAN: "Home = Athena's cards + his pinned mini apps and widgets"): "app:money,widget:weather/widgets/now.html",
 * kept in prefs apollo_ui home_pins. He pins by long-pressing an app in Apps; Athena sets it with phone_setting home_pins. Pure.
 */
object HomePins {
    const val CAP = 12
    const val PREF = "home_pins"

    /** [apps]: the mini apps on the phone, name to its widgets. Unknown apps and widgets are skipped, each pin once, at most [CAP]. */
    fun parse(raw: String?, apps: Map<String, List<String>>): List<Pin> = raw.orEmpty().split(',').map { it.trim() }.mapNotNull { w ->
        when {
            w.startsWith("app:") -> w.substring(4).takeIf { it in apps }?.let { Pin(it) }
            w.startsWith("widget:") -> w.substring(7).let { s -> Pin(s.substringBefore('/'), s.substringAfter('/', "")) }
                .takeIf { p -> apps[p.app]?.contains(p.widget) == true }
            else -> null
        }
    }.distinct().take(CAP)

    /** Pinned: unpin it; not: pin it at the end (the oldest one past the cap drops off). */
    fun toggle(raw: String?, pin: Pin): String {
        val now = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return (if (pin.wire in now) now - pin.wire else (now + pin.wire).takeLast(CAP)).joinToString(",")
    }

    fun has(raw: String?, pin: Pin) = raw.orEmpty().split(',').any { it.trim() == pin.wire }

    fun read(c: Context): String? = c.getSharedPreferences("apollo_ui", Context.MODE_PRIVATE).getString(PREF, null)

    /** His own change: kept, and told to Athena as a fact so she doesn't set it back. */
    fun set(c: Context, raw: String) {
        c.getSharedPreferences("apollo_ui", Context.MODE_PRIVATE).edit().putString(PREF, raw).apply()
        changed.value++
        dev.apollo.net.Facts.post(c, "home_pins", org.json.JSONObject().put("pins", raw))
    }

    /** Bumps when the pins change, so Home and Apps redraw. */
    val changed = kotlinx.coroutines.flow.MutableStateFlow(0)
}
