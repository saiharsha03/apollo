package dev.apollo.senses

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What an Allow button asks Android for, by Dawn's step key: the real permission prompt, Health Connect's own sheet, or
 * the exact settings screen. Senses and Dawn's page both go through it. Pure, apart from [declared].
 */
object Allow {
    sealed interface Way
    data class Perms(val list: List<String>) : Way
    data class Screen(val action: String, val withPackage: Boolean = false) : Way
    data object HealthAll : Way
    data object Folder : Way
    data object Car : Way
    data object GoogleHome : Way
    data object Widget : Way

    private const val HEALTH = "android.permission.health."

    /**
     * The fixed allowlist: a step key to its way, null for anything else (pair, brain_key, box_key, iris...). A `perm_`
     * key is one of the dangerous permissions Apollo itself asks for ([declared], from its manifest).
     */
    fun way(key: String, declared: List<String>): Way? = when (key) {
        in SetupRules.GROUPS -> Perms(SetupRules.GROUPS.getValue(key))
        "notifications" -> Screen(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "usage" -> Screen(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        "battery" -> Screen(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true)
        "health", "health_write" -> HealthAll
        "docs_folders" -> Folder
        "car" -> Car
        "google_home" -> GoogleHome
        "widget" -> Widget
        else -> if (key.startsWith("perm_")) declared.firstOrNull { SetupRules.permKey(it) == key }?.let { p ->
            if (p.startsWith(HEALTH)) HealthAll else Perms(listOf(p))
        } else null
    }

    /** A page may ask only from Dawn's full page (never a widget or another app), and only for a key on the list. */
    fun forPage(app: String?, fullPage: Boolean, key: String?, declared: List<String>): Way? =
        if (app == "dawn" && fullPage && key != null) way(key, declared) else null

    /** The main-frame token (AppWeb) matches; a frame without it gets nothing. */
    fun tokenOk(expected: String?, given: String?): Boolean =
        !expected.isNullOrEmpty() && given != null && java.security.MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())

    fun declared(c: Context): List<String> =
        runCatching { c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList() }.getOrNull().orEmpty()

    fun intent(c: Context, s: Screen): Intent = Intent(s.action).apply { if (s.withPackage) data = Uri.parse("package:" + c.packageName) }

    /** Android's own pin-a-widget sheet; false when the launcher has none. */
    fun pinWidget(c: Context): Boolean {
        val m = AppWidgetManager.getInstance(c)
        return m.isRequestPinAppWidgetSupported && m.requestPinAppWidget(ComponentName(c, dev.apollo.apps.ApolloWidget::class.java), null, null)
    }

    /** What the page hears after the pin sheet: not done yet either way; the status row ticks it once the widget is placed. */
    fun pinWords(asked: Boolean) = if (asked) "Drop it on your home screen; this ticks once it's there"
        else "Your launcher can't add it from here: long-press the home screen > Widgets > Apollo"

    /** A page's ask: [answer] gets the step's status right after Android's prompt or screen returns. */
    class Ask(val app: String, val key: String, val way: Way, val answer: (Boolean, String) -> Unit)
    val ask = MutableStateFlow<Ask?>(null)

    fun clear(app: String) { ask.value?.takeIf { it.app == app }?.let { ask.value = null; it.answer(false, "page closed") } }

    /** Re-reports Dawn's status at once (urgent) and says where [key] stands now. */
    suspend fun after(c: Context, key: String): Pair<Boolean, String> {
        runCatching { Setup.look(c, urgent = true) }
        // Coming back from a settings screen also runs the ordinary look, which may have queued the row first.
        runCatching { dev.apollo.net.Facts.uploadNow(c) }
        return runCatching { Setup.status(c)[key] }.getOrNull() ?: (false to "unknown")
    }
}

/** Over a page of [app]: runs what its Allow asks for, then answers the page with the fresh status. */
@Composable
fun AllowLayer(app: String) {
    val c = LocalContext.current
    val a by Allow.ask.collectAsState()
    val scope = rememberCoroutineScope()
    /** [say]: answer this instead of the fresh status (the widget isn't placed until he drops it on the home screen). */
    fun done(say: Pair<Boolean, String>? = null) {
        val cur = Allow.ask.value ?: return
        Allow.ask.value = null
        if (say != null) { cur.answer(say.first, say.second); return }
        scope.launch { val (ok, detail) = withContext(Dispatchers.IO) { Allow.after(c, cur.key) }; cur.answer(ok, detail) }
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { runCatching { Walking.arm(c) }; done() }
    val health = rememberLauncherForActivityResult(androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()) { Health.now(c); done() }
    val screen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { done() }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) runCatching { DocFolders.add(c, uri) }; done() }
    val ask = a?.takeIf { it.app == app } ?: return
    if (ask.way == Allow.Car) {
        Dialog(onDismissRequest = { done() }) {
            Column(Modifier.fillMaxWidth().background(Tone.raised, RoundedCornerShape(24.dp)).padding(20.dp)) {
                Text("Which one is your car?", style = Type.heading)
                BondedDevices { address, name -> Car.choose(c, address, name); done() }
            }
        }
        return
    }
    LaunchedEffect(ask) {
        runCatching {
            when (val w = ask.way) {
                is Allow.Perms -> perms.launch(w.list.toTypedArray())
                is Allow.Screen -> screen.launch(Allow.intent(c, w))
                Allow.HealthAll -> if (Health.available(c)) health.launch(Health.READ + HealthWrite.WRITE) else done()
                Allow.Folder -> folder.launch(null)
                Allow.GoogleHome -> { dev.apollo.house.House.get(c).requestPermission(); done() }
                Allow.Widget -> done(false to Allow.pinWords(Allow.pinWidget(c)))
                Allow.Car -> Unit
            }
        }.onFailure { done() }
    }
}
