package dev.apollo.senses

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.content.IntentCompat
import dev.apollo.clio.Clio
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Which bonded Bluetooth device is his car (he picks it on the Senses page). Only the car makes a drive. */
object Car {
    private fun p(c: Context) = c.getSharedPreferences("senses_car", Context.MODE_PRIVATE)
    fun address(c: Context): String? = p(c).getString("address", null)
    fun name(c: Context): String? = p(c).getString("name", null)
    fun choose(c: Context, address: String, name: String) = p(c).edit().putString("address", address).putString("name", name).apply()
}

/** The car's link coming up or going down starts and ends a drive, and is itself a `car` fact (the morning call waits for it). */
class CarLink : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(c: Context, i: Intent) {
        val on = connected(i.action) ?: return
        val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (dev.address != Car.address(c)) return
        Senses.sink.post("car", JSONObject().put("connected", on).put("state", if (on) "connected" else "disconnected").put("at", System.currentTimeMillis()), on)
        val svc = Intent(c, DriveService::class.java)
        if (!on) { c.stopService(svc); return }
        // A location foreground service without the location grant throws (Android 14+): the car fact still went.
        if (!DriveService.canRecord(c)) { Clio.log(c, "drive", "not recorded: no location access", JSONObject()); return }
        runCatching { c.startForegroundService(svc) }.onFailure { Clio.log(c, "drive", "not started", JSONObject().put("error", it.javaClass.simpleName)) }
    }

    companion object {
        /**
         * Only the two broadcasts the system sends count (only it can send protected ones): any other action, or none,
         * from another app is ignored, never read as "disconnected" (scan F12). Pure.
         */
        fun connected(action: String?): Boolean? = when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> null
        }
    }
}

/**
 * Records one drive while the car is connected, then posts one `trip` fact: the route (a point every 5 s and 15 m, with
 * its speed), the events [DriveSense] felt and the top speed. The motion sensors run only while a drive does.
 */
class DriveService : Service(), LocationListener, SensorEventListener {
    /** [lat, lng, t, speed m/s] */
    private val points = mutableListOf<DoubleArray>()
    private val sense = DriveSense()
    private var last: Location? = null
    private var start = 0L
    private var work: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (start != 0L) return START_NOT_STICKY
        start = System.currentTimeMillis()
        live.value = start
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Drive", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Recording this drive").setOngoing(true).build()
        if (runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }.isFailure) { start = 0L; live.value = null; stopSelf(); return START_NOT_STICKY }
        val h = HandlerThread("drive").also { it.start(); work = it }
        // 1 Hz: GPS-only braking needs fixes 0.5-3 s apart. The route itself is thinned in onLocationChanged.
        runCatching { getSystemService(LocationManager::class.java).requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, h.looper) }
        val sm = getSystemService(SensorManager::class.java)
        for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, 20_000, Handler(h.looper)) }
        return START_NOT_STICKY
    }

    override fun onLocationChanged(l: Location) {
        val prev = last
        val v = if (l.hasSpeed()) l.speed.toDouble()
            else if (prev != null && l.time > prev.time) prev.distanceTo(l) / ((l.time - prev.time) / 1000.0) else 0.0
        last = l
        synchronized(sense) {
            sense.gps(l.time, v, l.latitude, l.longitude)
            val p = points.lastOrNull()
            if (p == null || (l.time - p[2] >= 5_000 && Trips.haversine(p[0] to p[1], l.latitude to l.longitude) * 1609.344 >= 15)) {
                points += doubleArrayOf(l.latitude, l.longitude, l.time.toDouble(), v)
                miles.value = Trips.miles(points.map { it[0] to it[1] })
            }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val t = System.currentTimeMillis()
        val (x, y, z) = Triple(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
        synchronized(sense) { if (e.sensor.type == Sensor.TYPE_GYROSCOPE) sense.gyro(t, x, y, z) else sense.accel(t, x, y, z) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        live.value = null
        runCatching { getSystemService(SensorManager::class.java).unregisterListener(this) }
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        work?.quitSafely()
        synchronized(sense) {
            if (start != 0L && points.size >= 2) {
                val end = System.currentTimeMillis()
                Senses.sink.post("trip", fact(start, end, points, sense), false)
            }
        }
        super.onDestroy()
    }

    companion object {
        fun canRecord(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** The `trip` fact. Pure. */
        fun fact(start: Long, end: Long, points: List<DoubleArray>, sense: DriveSense): JSONObject = JSONObject()
            .put("start", start).put("end", end)
            .put("miles", Trips.miles(points.map { it[0] to it[1] }))
            .put("minutes", (end - start) / 60_000.0)
            .put("points", JSONArray(points.map { JSONArray().put(it[0]).put(it[1]).put(it[2].toLong()).put((it[3] * 10).roundToInt() / 10.0) }))
            .put("events", JSONArray(sense.events.map { it.json() }))
            .put("max_mph", (sense.maxSpeed * 2.23694 * 10).roundToInt() / 10.0)
            .put("mounted", sense.mounted)

        /** When the drive now recording started (null when none), and its miles so far: the island shows them. */
        val live = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
        val miles = kotlinx.coroutines.flow.MutableStateFlow(0.0)
        private const val CHANNEL = "drive"
        const val ID = 4402
    }
}

/** Distance along the recorded points. Pure. */
object Trips {
    private const val EARTH_MILES = 3958.8

    fun miles(points: List<Pair<Double, Double>>): Double = points.zipWithNext().sumOf { (a, b) -> haversine(a, b) }

    fun haversine(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = Math.PI / 180
        val dLat = (b.first - a.first) * r
        val dLng = (b.second - a.second) * r
        val h = sin(dLat / 2).pow(2) + cos(a.first * r) * cos(b.first * r) * sin(dLng / 2).pow(2)
        return 2 * EARTH_MILES * asin(sqrt(h))
    }
}
