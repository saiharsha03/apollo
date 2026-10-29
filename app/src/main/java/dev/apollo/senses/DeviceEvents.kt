package dev.apollo.senses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.apollo.clio.Clio
import org.json.JSONObject

/** When a device event is a fact. Pure. */
object EventRules {
    /** Charged: plugged in and full (100%, or the charger says full), told once per plug-in. */
    fun full(level: Int, status: Int, plugged: Boolean, told: Boolean): Boolean =
        plugged && !told && (level >= 100 || status == BatteryManager.BATTERY_STATUS_FULL)

    /** Android quotes an SSID and hides it as "<unknown ssid>" without location access. */
    fun ssid(raw: String?): String? = raw?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

    /** Leaving a network counts only when it stays gone this long (a wall between him and the router is not leaving). */
    const val LEFT_AFTER_MS = 60_000L
}

/**
 * Device triggers, as facts (Zeus's WakeOn events, without the deciding): `charging` {state plugged|unplugged, level},
 * `charged` {level} once per plug-in, `bluetooth` {state connected|disconnected, name, car} for audio devices (buds,
 * speakers, the car), `wifi` {state joined|left, ssid}. Routines can trigger on them (charging:plugged, wifi:left).
 * Registered while the notification listener is bound, which keeps Apollo's process alive.
 */
object DeviceEvents {
    private var receiver: BroadcastReceiver? = null
    private var wifi: ConnectivityManager.NetworkCallback? = null
    private val main = Handler(Looper.getMainLooper())
    private var pendingLeft: Runnable? = null
    private fun prefs(c: Context) = c.getSharedPreferences("senses_events", Context.MODE_PRIVATE)

    fun start(c: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) { runCatching { on(c, i) } }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        // System broadcasts arrive either way; not exported keeps other apps from faking one.
        ContextCompat.registerReceiver(c, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)   // the flag is API 33; minSdk is 31
        receiver = r
        runCatching { watchWifi(c) }.onFailure { Clio.log(c, "events", "wifi not watched", JSONObject().put("error", it.javaClass.simpleName)) }
    }

    fun stop(c: Context) {
        receiver?.let { runCatching { c.unregisterReceiver(it) } }
        receiver = null
        wifi?.let { runCatching { c.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        wifi = null
    }

    private fun level(c: Context) = c.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    @SuppressLint("MissingPermission")
    private fun on(c: Context, i: Intent) {
        val p = prefs(c)
        when (i.action) {
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                val plugged = i.action == Intent.ACTION_POWER_CONNECTED
                p.edit().putBoolean("full_told", false).apply()
                Senses.sink.post("charging", JSONObject().put("state", if (plugged) "plugged" else "unplugged").put("level", level(c)), false)
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                if (EventRules.full(lvl, i.getIntExtra(BatteryManager.EXTRA_STATUS, -1), plugged, p.getBoolean("full_told", false))) {
                    p.edit().putBoolean("full_told", true).apply()
                    Senses.sink.post("charged", JSONObject().put("level", lvl), false)
                }
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state != BluetoothProfile.STATE_CONNECTED && state != BluetoothProfile.STATE_DISCONNECTED) return
                val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                val name = runCatching { dev.name }.getOrNull() ?: dev.address
                Senses.sink.post("bluetooth", JSONObject().put("state", if (state == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected")
                    .put("name", name).put("audio", true).put("car", dev.address == Car.address(c)), false)
            }
        }
    }

    /** Wi-Fi joins and leaves. The SSID needs location access (granted for drives); without it the fact says null. */
    private fun watchWifi(c: Context) {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ssid = EventRules.ssid((caps.transportInfo as? WifiInfo)?.ssid)
                main.post { joined(c, ssid) }
            }
            override fun onLost(network: Network) { main.post { lost(c) } }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        wifi = cb
    }

    private fun joined(c: Context, ssid: String?) {
        pendingLeft?.let { main.removeCallbacks(it) }
        pendingLeft = null
        val p = prefs(c)
        if (p.getBoolean("wifi_on", false) && p.getString("ssid", null) == ssid) return
        p.edit().putBoolean("wifi_on", true).putString("ssid", ssid).apply()
        Senses.sink.post("wifi", JSONObject().put("state", "joined").put("ssid", ssid ?: JSONObject.NULL), false)
    }

    private fun lost(c: Context) {
        if (pendingLeft != null) return
        val r = Runnable {
            pendingLeft = null
            val p = prefs(c)
            if (!p.getBoolean("wifi_on", false)) return@Runnable
            val ssid = p.getString("ssid", null)
            p.edit().putBoolean("wifi_on", false).apply()
            Senses.sink.post("wifi", JSONObject().put("state", "left").put("ssid", ssid ?: JSONObject.NULL), false)
        }
        pendingLeft = r
        main.postDelayed(r, EventRules.LEFT_AFTER_MS)
    }
}
