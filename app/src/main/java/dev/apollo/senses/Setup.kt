package dev.apollo.senses

import android.Manifest
import android.app.AppOpsManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.PowerManager
import android.provider.Settings
import dev.apollo.calypso.SealedSecret
import dev.apollo.net.Server
import org.json.JSONObject

/** Which status rows Dawn gets and when one is news. Pure. */
object SetupRules {
    /** Dawn's own step keys built from several permissions (server/seed/dawn). */
    val GROUPS: Map<String, List<String>> = mapOf(
        "location" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS),
        "background_location" to listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        "calendar" to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        "call_log" to listOf(Manifest.permission.READ_CALL_LOG),
        "contacts" to listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
        "activity" to listOf(Manifest.permission.ACTIVITY_RECOGNITION),
    )

    /** The keys Calypso should hold, with what each is for (Zeus's KeyProbes slots, Apollo's own device token aside). */
    val SLOTS = listOf("anthropic", "anthropic_admin", "tomtom", "ny511", "cloudhost", "spotify")

    /** One permission's own row: perm_ and its last name part, lower case (perm_read_contacts). */
    fun permKey(p: String): String = "perm_" + p.substringAfterLast('.').lowercase()

    /** The rows that changed since the last report ([told]: key to ok). */
    fun changed(now: Map<String, Boolean>, told: Map<String, Boolean>): Map<String, Boolean> = now.filter { (k, v) -> told[k] != v }
}

/**
 * Dawn's status table, from what Apollo can see of its own setup: `setup` facts {key, ok, detail}, only when a row
 * changes. Every permission Apollo asks for (perm_*), Dawn's grouped steps, the notification listener, usage access,
 * battery optimisation, paired, Calypso's key slots (key_*, present or not, never the key), the car, Health Connect,
 * Google Home, Spotify, the widget and Zeus's apps moving over. Runs when Apollo comes to the front and every 15
 * minutes from the listener's loop. `box_key` and `iris` live on the server: Athena marks them.
 */
object Setup {
    private fun prefs(c: Context) = c.getSharedPreferences("senses_setup", Context.MODE_PRIVATE)

    /** [urgent]: right after an Allow prompt returns, so Dawn ticks the step now rather than at the next upload. */
    suspend fun look(c: Context, urgent: Boolean = false) {
        val now = status(c)
        val p = prefs(c)
        val told = p.all.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap()
        val news = SetupRules.changed(now.mapValues { it.value.first }, told)
        if (news.isEmpty()) return
        p.edit().apply { news.forEach { (k, v) -> putBoolean(k, v) } }.apply()
        for ((k, ok) in news) Senses.sink.post("setup", JSONObject().put("key", k).put("ok", ok).put("detail", now.getValue(k).second), urgent)
    }

    /** Key to (ok, detail). */
    suspend fun status(c: Context): Map<String, Pair<Boolean, String>> {
        val out = linkedMapOf<String, Pair<Boolean, String>>()
        fun granted(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val pm = c.packageManager
        val declared = runCatching { pm.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList() }.getOrNull().orEmpty()
        for (p in declared) {
            val dangerous = runCatching { pm.getPermissionInfo(p, 0).protection == PermissionInfo.PROTECTION_DANGEROUS }.getOrDefault(false)
            if (dangerous) out[SetupRules.permKey(p)] = granted(p) to (if (granted(p)) "allowed" else "not allowed")
        }
        for ((k, ps) in SetupRules.GROUPS) if (ps.all { it in declared }) {
            val missing = ps.filterNot(::granted).map { it.substringAfterLast('.').lowercase() }
            out[k] = missing.isEmpty() to (if (missing.isEmpty()) "allowed" else "missing: " + missing.joinToString(", "))
        }
        val listener = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty().contains(ComponentName(c, Listener::class.java).flattenToString())
        out["notifications"] = listener to (if (listener) "listener on" else "listener off")
        val usage = c.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
        out["usage"] = usage to (if (usage) "allowed" else "not allowed")
        val battery = c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true
        out["battery"] = battery to (if (battery) "unrestricted" else "optimised: Android may stop Apollo in the background")
        val paired = SealedSecret(c, Server.TOKEN_SLOT).has()
        out["pair"] = paired to (if (paired) "device token in Calypso" else "not paired")
        out["brain_key"] = SealedSecret(c, "anthropic").has().let { it to (if (it) "in Calypso" else "no Anthropic key in Calypso") }
        for (s in (SetupRules.SLOTS + SealedSecret.slots(c)).distinct()) if (s != Server.TOKEN_SLOT) {
            val has = SealedSecret(c, s).has()
            out["key_$s"] = has to (if (has) "present" else "empty")
        }
        out["car"] = Car.name(c).let { (it != null) to (it ?: "no car picked") }
        if (Health.available(c)) {
            val g = Health.granted(c)
            val missing = Health.READ.count { it !in g }
            out["health"] = (missing == 0) to (if (missing == 0) "all reads allowed" else "$missing reads not allowed")
            val w = HealthWrite.WRITE.count { it !in g }
            out["health_write"] = (w == 0) to (if (w == 0) "food and weight logging allowed" else "logging not allowed")
        } else out["health"] = false to "Health Connect isn't on this phone"
        when (val link = runCatching { dev.apollo.house.House.get(c).state.value.link }.getOrNull()) {
            is dev.apollo.house.Link.Connected -> out["google_home"] = true to "connected"
            is dev.apollo.house.Link.NotConnected -> out["google_home"] = false to link.reason.take(120)
            dev.apollo.house.Link.NeedsPermission -> out["google_home"] = false to "needs his sign-in"
            else -> {}
        }
        out["spotify"] = runCatching { dev.apollo.muse.SpotifyTokens(c).signedIn() }.getOrDefault(false).let { it to (if (it) "signed in" else "not signed in") }
        val widgets = runCatching { AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, dev.apollo.apps.ApolloWidget::class.java)).size }.getOrDefault(0)
        out["widget"] = (widgets > 0) to "$widgets placed"
        val zeus = c.getSharedPreferences("zeus_apps", Context.MODE_PRIVATE).getBoolean("sent", false)
        out["zeus_import"] = zeus to (if (zeus) "sent to the server" else "not sent yet")
        out["docs_folders"] = DocFolders.list(c).size.let { (it > 0) to "$it folders" }
        return out
    }
}
