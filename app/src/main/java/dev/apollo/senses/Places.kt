package dev.apollo.senses

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import dev.apollo.clio.Clio
import dev.apollo.net.APPS_DIR
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One of his saved places (Athena keeps them in the drives app's `places` table). */
data class Place(val id: Long, val name: String, val lat: Double, val lng: Double, val radiusM: Double)

/**
 * A stay: at least [minMs] within [radiusM] of where it began, on foot or not. Pure: fed one location fix at a time, it
 * says when a stay has lasted long enough ([Out.Here]) and when he left it ([Out.Left]). A fix too vague to place him
 * (accuracy worse than [worstM]) is ignored.
 */
class StayWatch(private val minMs: Long = 5 * 60_000L, private val radiusM: Double = 120.0, private val worstM: Float = 400f) {
    sealed interface Out {
        data class Here(val start: Long, val lat: Double, val lng: Double) : Out
        data class Left(val start: Long, val end: Long, val lat: Double, val lng: Double) : Out
    }

    /** Where the current stay began, its latest fix, the running centre and whether it was already reported. */
    var state: State? = null
        private set

    data class State(val lat0: Double, val lng0: Double, val start: Long, val last: Long, val sumLat: Double, val sumLng: Double, val n: Int, val told: Boolean) {
        val lat get() = sumLat / n
        val lng get() = sumLng / n
    }

    fun restore(s: State?) { state = s }

    fun fix(lat: Double, lng: Double, at: Long, accuracyM: Float): Out? {
        if (accuracyM > worstM) return null
        val s = state
        if (s == null) { state = State(lat, lng, at, at, lat, lng, 1, false); return null }
        if (at <= s.last) return null   // the same fix again (last known location repeats)
        if (Trips.haversine(s.lat0 to s.lng0, lat to lng) * METERS_PER_MILE <= radiusM) {
            val next = s.copy(last = at, sumLat = s.sumLat + lat, sumLng = s.sumLng + lng, n = s.n + 1)
            if (!s.told && at - s.start >= minMs) { state = next.copy(told = true); return Out.Here(s.start, next.lat, next.lng) }
            state = next
            return null
        }
        state = State(lat, lng, at, at, lat, lng, 1, false)
        return if (s.told) Out.Left(s.start, s.last, s.lat, s.lng) else null
    }

    /** Driving: a stop at a light is not a stay. Forgets the current one without reporting it. */
    fun reset() { state = null }

    companion object {
        const val METERS_PER_MILE = 1609.344

        /** The saved place a spot is in, the nearest when circles overlap. Pure. */
        fun placeAt(lat: Double, lng: Double, places: List<Place>): Place? =
            places.map { it to Trips.haversine(lat to lng, it.lat to it.lng) * METERS_PER_MILE }.filter { (p, d) -> d <= p.radiusM }.minByOrNull { it.second }?.first
    }
}

/**
 * Stays and places, battery-cheap: fused location at balanced power every few minutes (Wi-Fi and cell, no GPS of its
 * own), the last known fix every minute from the listener's loop (free: it reuses fixes other apps asked for), and a
 * geofence per saved place for arrive and leave. Facts: `stay` (here after 5 minutes, left with the span) and `place`
 * (arrive / leave). Nothing decided: Athena learns his places from the stays and writes them back.
 */
object Places {
    private const val FIX = "dev.apollo.senses.FIX"
    private const val FENCE = "dev.apollo.senses.FENCE"
    private val watch = StayWatch()
    private var loaded = false

    private fun prefs(c: Context) = c.getSharedPreferences("senses_places", Context.MODE_PRIVATE)

    fun allowed(c: Context): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

    private fun pending(c: Context, action: String, code: Int) = PendingIntent.getBroadcast(c, code, Intent(c, PlaceReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** At app start and when the listener connects: the balanced-power updates, and the geofences as they are. */
    @SuppressLint("MissingPermission")
    fun start(c: Context) {
        if (!allowed(c)) return
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, TimeUnit.MINUTES.toMillis(5))
            .setMinUpdateIntervalMillis(TimeUnit.MINUTES.toMillis(2)).build()
        runCatching { LocationServices.getFusedLocationProviderClient(c).requestLocationUpdates(req, pending(c, FIX, 1)) }
        // Geofences don't survive a reboot or an update: registered again on every start, off the main thread (it waits on Play services).
        prefs(c).edit().remove("fences").apply()
        Thread { runCatching { fences(c) } }.start()
    }

    /** From the listener's minute loop: the last known fix, and the geofences again when his places changed. */
    @SuppressLint("MissingPermission")
    fun look(c: Context) {
        if (!allowed(c)) return
        runCatching { Tasks.await(LocationServices.getFusedLocationProviderClient(c).lastLocation, 10, TimeUnit.SECONDS) }.getOrNull()?.let { fix(c, it) }
        fences(c)
    }

    @Synchronized
    fun fix(c: Context, l: Location) {
        if (!loaded) { watch.restore(load(c)); loaded = true }
        if (DriveService.live.value != null) { watch.reset(); save(c, null); return }
        val out = watch.fix(l.latitude, l.longitude, l.time, if (l.hasAccuracy()) l.accuracy else 0f)
        save(c, watch.state)
        val places by lazy { places(c) }
        when (out) {
            is StayWatch.Out.Here -> Senses.sink.post("stay", JSONObject().put("state", "here").put("start", out.start).put("lat", out.lat).put("lng", out.lng)
                .put("place", StayWatch.placeAt(out.lat, out.lng, places)?.name ?: JSONObject.NULL), false)
            is StayWatch.Out.Left -> Senses.sink.post("stay", JSONObject().put("state", "left").put("start", out.start).put("end", out.end)
                .put("minutes", (out.end - out.start) / 60_000L).put("lat", out.lat).put("lng", out.lng)
                .put("place", StayWatch.placeAt(out.lat, out.lng, places)?.name ?: JSONObject.NULL), false)
            null -> {}
        }
    }

    /** His saved places, from the drives app's copy on the phone (Athena writes them; the phone only reads). */
    fun places(c: Context): List<Place> {
        val f = File(c.filesDir, "$APPS_DIR/drives/data.db")
        if (!f.isFile) return emptyList()
        return runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT id, name, lat, lng, COALESCE(radius_m, 150) FROM places", null).use { k ->
                    buildList { while (k.moveToNext()) add(Place(k.getLong(0), k.getString(1), k.getDouble(2), k.getDouble(3), k.getDouble(4))) }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** One geofence per place (at least 100 m: Android's fences are not sharper). Re-registered only when the list changes. */
    @SuppressLint("MissingPermission")
    private fun fences(c: Context) {
        val list = places(c)
        val key = list.joinToString("|") { "${it.id}:${it.lat}:${it.lng}:${it.radiusM}" }
        if (key == prefs(c).getString("fences", null)) return
        val client = LocationServices.getGeofencingClient(c)
        val pi = pending(c, FENCE, 2)
        runCatching { Tasks.await(client.removeGeofences(pi), 10, TimeUnit.SECONDS) }
        val ok = list.isEmpty() || runCatching {
            // No initial trigger: registering again while he is there must not say he arrived again.
            val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(list.map {
                Geofence.Builder().setRequestId(it.id.toString()).setCircularRegion(it.lat, it.lng, it.radiusM.coerceAtLeast(100.0).toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE).setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).build()
            }).build()
            Tasks.await(client.addGeofences(req, pi), 10, TimeUnit.SECONDS); true
        }.getOrDefault(false)
        if (ok) prefs(c).edit().putString("fences", key).apply()
    }

    fun fenced(c: Context, i: Intent) {
        val e = GeofencingEvent.fromIntent(i) ?: return
        if (e.hasError()) { Clio.log(c, "places", "geofence error", JSONObject().put("code", e.errorCode)); return }
        val event = when (e.geofenceTransition) { Geofence.GEOFENCE_TRANSITION_ENTER -> "arrive"; Geofence.GEOFENCE_TRANSITION_EXIT -> "leave"; else -> return }
        val byId = places(c).associateBy { it.id.toString() }
        e.triggeringGeofences.orEmpty().forEach { g ->
            val p = byId[g.requestId] ?: return@forEach
            Senses.sink.post("place", JSONObject().put("event", event).put("name", p.name).put("id", p.id).put("at", e.triggeringLocation?.time ?: System.currentTimeMillis()), false)
        }
    }

    fun onIntent(c: Context, i: Intent) {
        when (i.action) {
            FIX -> LocationResult.extractResult(i)?.locations.orEmpty().sortedBy { it.time }.forEach { fix(c, it) }
            FENCE -> fenced(c, i)
        }
    }

    private fun save(c: Context, s: StayWatch.State?) {
        prefs(c).edit().putString("stay", s?.let { "${it.lat0},${it.lng0},${it.start},${it.last},${it.sumLat},${it.sumLng},${it.n},${it.told}" }).apply()
    }

    private fun load(c: Context): StayWatch.State? = prefs(c).getString("stay", null)?.split(',')?.takeIf { it.size == 8 }?.let { p ->
        runCatching { StayWatch.State(p[0].toDouble(), p[1].toDouble(), p[2].toLong(), p[3].toLong(), p[4].toDouble(), p[5].toDouble(), p[6].toInt(), p[7].toBoolean()) }.getOrNull()
    }
}

/** Fused location updates and geofence transitions, both into [Places]. */
class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val done = goAsync()
        Thread { runCatching { Places.onIntent(c.applicationContext, i) }; done.finish() }.start()
    }
}
