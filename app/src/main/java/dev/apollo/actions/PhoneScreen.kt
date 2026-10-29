package dev.apollo.actions

import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.apollo.ui.Action
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type

/**
 * More > Phone: the one-time Android OKs Apollo's actions need (lock the screen, Do Not Disturb, run in the background)
 * and his NFC tags. Each row says what it is for; nothing is turned on without his own tap in Android's screen.
 */
@Composable
fun PhoneScreen() {
    val c = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var fresh by remember { mutableIntStateOf(0) }
    // Back from Android's screens: read the states again.
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) fresh++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val lock = remember(fresh) { MoreActions.canLock(c) }
    val dnd = remember(fresh) { c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }
    val battery = remember(fresh) { c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) }
    var tags by remember { mutableStateOf(Tags.all(c)) }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    fun go(i: Intent) = runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Phone", style = Type.title)
        Text("What Apollo may do on the phone itself. Each opens Android's own screen.", style = Type.small)
        Spacer(Modifier.height(12.dp))
        Row2("Lock the screen", "When you fall asleep watching, and when Athena locks it. It can only lock.", if (lock) "On" else "Turn on") {
            if (!lock) go(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, MoreActions.admin(c))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Apollo locks the screen when you fall asleep watching, or when Athena asks. Nothing else."))
        }
        Row2("Do Not Disturb", "Hermes and Athena turn it on and off; critical calls ring through it", if (dnd) "On" else "Turn on") {
            go(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        Row2("Run in the background", "Senses, timed reminders and calls keep working when the phone sleeps", if (battery) "On" else "Turn on") {
            if (!battery) go(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")))
        }
        Spacer(Modifier.height(20.dp))
        Text("NFC tags", style = Type.heading)
        Text("A sticker at your bed, door or desk. A tap tells Athena its name; she gives it a job as a routine.", style = Type.small)
        note?.let { Text(it, style = Type.small.copy(color = Tone.alarm)) }
        tags.forEach { (id, n) -> Row2(n, null, "Remove") { Tags.remove(c, id); tags = Tags.all(c) } }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicTextField(name, { name = it.take(30) }, textStyle = Type.body, cursorBrush = SolidColor(Tone.saffron), singleLine = true,
                modifier = Modifier.weight(1f).background(Tone.soft, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                decorationBox = { inner -> if (name.isEmpty()) Text("Name, like bed", style = Type.body.copy(color = Tone.quiet)); inner() })
            Action("Write a tag", enabled = name.isNotBlank()) {
                val a = c as? Activity ?: return@Action
                note = "Hold the sticker to the back of the phone"
                Tags.write(a, name) { err -> note = err ?: "Tag \"${name.trim().lowercase()}\" written"; if (err == null) { name = ""; tags = Tags.all(c) } }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { (c as? Activity)?.let { Tags.stopWrite(it) } } }
}
