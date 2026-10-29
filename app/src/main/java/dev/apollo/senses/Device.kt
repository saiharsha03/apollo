package dev.apollo.senses

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.BatteryManager
import org.json.JSONObject

/** When the battery is worth a fact. Pure. */
object BatteryRules {
    val STEPS = listOf(20, 10, 5)

    /**
     * The low step [level] has fallen to, or null. [told] is the lowest step already reported since the last charge
     * (101 when none): each step is said once, and plugging in starts over.
     */
    fun low(level: Int, charging: Boolean, told: Int): Int? =
        if (charging) null else STEPS.filter { level <= it && it < told }.minOrNull()

    /** Interruption filter to words. */
    fun focus(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms only"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "total silence"
        else -> "off"
    }
}

/**
 * Small phone states, from the listener's minute loop: `battery` when it falls to 20, 10 or 5 percent unplugged; `alarm`
 * when his next clock alarm changes (any clock app, through AlarmManager), and its ring ([AlarmRing]); `focus` when Do Not Disturb or a mode that
 * silences him turns on or off (the listener's own callback, see [focusChanged]).
 */
object DeviceWatch {
    private fun prefs(c: Context) = c.getSharedPreferences("senses_device", Context.MODE_PRIVATE)

    fun look(c: Context) {
        battery(c)
        alarm(c)
    }

    private fun battery(c: Context) {
        val bm = c.getSystemService(BatteryManager::class.java) ?: return
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: return
        val charging = bm.isCharging
        val p = prefs(c)
        if (charging) { p.edit().putInt("told", 101).apply(); return }
        val step = BatteryRules.low(level, false, p.getInt("told", 101)) ?: return
        p.edit().putInt("told", step).apply()
        Senses.sink.post("battery", JSONObject().put("level", level).put("charging", false).put("low", true), step <= 10)
    }

    private fun alarm(c: Context) {
        val next = c.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        val at = next?.triggerTime ?: 0L
        val p = prefs(c)
        val before = p.getLong("alarm", -1L)
        if (at != before) {
            p.edit().putLong("alarm", at).putLong("alarm_before", before.coerceAtLeast(0L)).apply()
            if (AlarmRules.wentOff(before, at, System.currentTimeMillis())) AlarmRing.wentOff(c, before)
        }
        AlarmRing.look(c, at)
        if (at == before) return
        Senses.sink.post("alarm", JSONObject().put("next_at", if (at > 0) at else JSONObject.NULL)
            .put("app", next?.showIntent?.creatorPackage ?: JSONObject.NULL), false)
    }

    /** NotificationListenerService.onInterruptionFilterChanged, and once when the listener connects. */
    fun focusChanged(c: Context, filter: Int) {
        val mode = BatteryRules.focus(filter)
        val p = prefs(c)
        if (mode == p.getString("focus", null)) return
        p.edit().putString("focus", mode).apply()
        Senses.sink.post("focus", JSONObject().put("on", mode != "off").put("mode", mode), false)
    }
}
