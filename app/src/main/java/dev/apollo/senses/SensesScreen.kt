package dev.apollo.senses

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type

/** What Apollo can sense, what's missing, and which Bluetooth device is the car. */
@Composable
fun SensesScreen() {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++; runCatching { Walking.arm(c) } }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) { runCatching { DocFolders.add(c, uri) }; tick++ } }
    val askHealth = rememberLauncherForActivityResult(androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()) { tick++; Health.now(c) }
    val health by produceState(emptySet<String>(), tick) { value = Health.granted(c) }
    fun granted(p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    val listener = remember(tick) {
        Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty().contains(ComponentName(c, Listener::class.java).flattenToString())
    }
    val usage = remember(tick) {
        c.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(24.dp))
        Text("Senses", style = Type.title)
        Text("What Apollo can see, to tell Athena.", style = Type.small)
        Spacer(Modifier.height(12.dp))
        fun state(ok: Boolean) = if (ok) "On" else "Turn on"
        Row2("Notifications", "Messages and alerts, full text (never codes)", state(listener)) {
            c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        Row2("Usage", "Unlocks, apps used, sleep", state(usage)) { c.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        val basics = listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        Row2("Location, Bluetooth, alerts", "For drives and asking you things", state(basics.all(::granted))) { ask.launch(basics.toTypedArray()) }
        Row2("Location all the time", "So drives record with the screen off, and stays and places work", state(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))) {
            ask.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
        val cal = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        Row2("Calendar", "Your Google calendar and the phone's; changes only after you tap Yes", state(cal.all(::granted))) { ask.launch(cal.toTypedArray()) }
        val contacts = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        Row2("Contacts", "Names for calls and texts; a birthday goes on a contact only after you tap Yes", state(contacts.all(::granted))) { ask.launch(contacts.toTypedArray()) }
        Row2("Health Connect", if (Health.available(c)) "Steps, sleep, heart rate and workouts" else "Health Connect isn't on this phone",
            state(Health.READ.all { it in health })) { if (Health.available(c)) askHealth.launch(Health.READ + HealthWrite.WRITE) }
        Row2("Calls", "Your call log and WhatsApp calls, for routines (never people sealed in Calypso)", state(granted(Manifest.permission.READ_CALL_LOG))) {
            ask.launch(arrayOf(Manifest.permission.READ_CALL_LOG))
        }
        Row2("Walking", "Tells Athena when you start and stop walking", state(granted(Manifest.permission.ACTIVITY_RECOGNITION))) {
            ask.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
        }
        Row2("Folders for Athena", "PDF, Word and text files in folders you pick go to Athena", "Add") { runCatching { pickFolder.launch(null) } }
        val folders = remember(tick) { DocFolders.list(c).sorted() }
        folders.forEach { t -> Row2(DocFolders.label(t), "Tap to stop sharing this folder", "Remove") { DocFolders.remove(c, t); tick++ } }
        Row2("Your car", Car.name(c) ?: "Pick the car's Bluetooth", if (picking) "Close" else "Pick") { picking = !picking }
        if (picking) BondedDevices { address, name -> Car.choose(c, address, name); picking = false; tick++ }
        Spacer(Modifier.height(20.dp))
        BankSendersSection()
        Spacer(Modifier.height(40.dp))
    }
}

@SuppressLint("MissingPermission")
@Composable
internal fun BondedDevices(pick: (String, String) -> Unit) {
    val c = LocalContext.current
    val devices = remember {
        runCatching { c.getSystemService(BluetoothManager::class.java).adapter.bondedDevices.map { it.address to (it.name ?: it.address) } }.getOrDefault(emptyList())
    }
    if (devices.isEmpty()) Text("No paired devices (or Bluetooth permission is off).", style = Type.small.copy(color = Tone.alarm))
    devices.forEach { (address, name) -> Row2(name, address) { pick(address, name) } }
}
