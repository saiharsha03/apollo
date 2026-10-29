package dev.apollo.senses

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One driving event: what (a [DriveSense] kind), when, where (the last GPS fix), and its peak g (mph for speeding). */
data class DriveEvent(val kind: String, val t: Long, val lat: Double, val lng: Double, val value: Double) {
    fun json(): JSONObject = JSONObject().put("kind", kind).put("t", t).put("lat", lat).put("lng", lng).put("value", value)
}

/**
 * Zeus's drive sensing (odyssey/Drive.kt), facts only: hard braking, rapid acceleration, sharp turns, speeding and the
 * phone picked up, from the accelerometer (~50 Hz), gyroscope and 1 Hz GPS while a drive is on. Pure; no scoring here.
 *
 * Mount: gravity is a slow low-pass of the accelerometer; the car's forward axis is the horizontal push that lines up with
 * GPS speed changes, learned again when gravity's direction shifts (the phone moved or was picked up). Thresholds: 0.3 g
 * along the car, 0.35 g across it, held 0.4 s. Until the mount is learned, braking and launches come from GPS alone.
 * Speeding has no speed-limit lookup: 10 s at more than 10 mph over the road's usual speed (the posted step just above
 * the median of the last 3 minutes), or above 80 mph anywhere.
 */
class DriveSense {
    companion object {
        const val G = 9.80665
        const val BRAKE_G = 0.30
        const val ACCEL_G = 0.30
        const val TURN_G = 0.35
        const val HOLD_MS = 400L
        const val MOVING_MS = 8 / 3.6
        const val BRAKE = "hard_brake"
        const val ACCEL = "rapid_accel"
        const val TURN = "sharp_turn"
        const val SPEEDING = "speeding"
        const val HANDLING = "phone_handling"
        private val LIMITS = intArrayOf(25, 30, 35, 40, 45, 55, 65, 70)
    }

    private var gx = 0.0; private var gy = 0.0; private var gz = 0.0
    private var gReady = 0
    private var refGx = 0.0; private var refGy = 0.0; private var refGz = 0.0
    private var fx = 0.0; private var fy = 0.0; private var fz = 0.0
    private var fVotes = 0
    private var sumX = 0.0; private var sumY = 0.0; private var sumZ = 0.0; private var sumN = 0
    private val win = ArrayDeque<DoubleArray>()
    private var winLong = 0.0; private var winLat = 0.0
    private var qx = 0.0; private var qy = 0.0; private var qz = 0.0
    private var twistAt = 0L; private var twisted = 0.0; private var lastGyroAt = 0L; private var gyroSince = 0L

    private var speed = 0.0
    private var lastLat = 0.0; private var lastLng = 0.0; private var lastGpsAt = 0L; private var lastSpeed = Double.NaN
    private val recent = ArrayDeque<Pair<Long, Double>>()
    private var speedingSince = 0L; private var speedingFlagged = false

    val events = ArrayList<DriveEvent>()
    private val lastOf = HashMap<String, Long>()
    private val heldSince = HashMap<String, Long>()
    private val peak = HashMap<String, Double>()

    /** Fastest GPS speed seen, m/s. */
    var maxSpeed = 0.0
        private set
    /** True while the forward axis is known (braking from the accelerometer, not GPS alone). */
    val aligned: Boolean get() = fVotes >= 3
    /** The mount was learned at some point in the drive (false: every brake and launch came from GPS alone). */
    var mounted = false
        private set

    fun accel(t: Long, x: Double, y: Double, z: Double) {
        // ~4 s time constant at 50 Hz, nearly frozen while the car is pushing the phone, so the push isn't read as gravity.
        val dx = x - gx; val dy = y - gy; val dz = z - gz
        val a = if (gReady < 50) 0.1 else if (sqrt(dx * dx + dy * dy + dz * dz) > 0.12 * G) 0.0002 else 0.005
        gx += a * (x - gx); gy += a * (y - gy); gz += a * (z - gz)
        qx += 0.1 * (x - qx); qy += 0.1 * (y - qy); qz += 0.1 * (z - qz)
        gReady++
        if (gReady == 100) { refGx = gx; refGy = gy; refGz = gz }
        if (gReady < 100) return
        // A tilt just ended (the gyroscope saw the phone turn, not the car): gravity is where it is now.
        if (twistAt > 0 && t - twistAt >= 500L) {
            if (twisted > Math.toRadians(20.0)) {
                if (speed > MOVING_MS) flag(HANDLING, t, 0.0, 10_000L)
                remount(qx, qy, qz)
            }
            twistAt = 0L; twisted = 0.0
        }
        // No gyroscope, or a slow slide in the mount: gravity drifted more than 25 degrees.
        if (angle(gx, gy, gz, refGx, refGy, refGz) > 25.0) {
            if (speed > MOVING_MS) flag(HANDLING, t, 0.0, 10_000L)
            remount(gx, gy, gz)
        }
        val gn = sqrt(gx * gx + gy * gy + gz * gz).takeIf { it > 1e-6 } ?: return
        val ux = gx / gn; val uy = gy / gn; val uz = gz / gn
        val lx = x - gx; val ly = y - gy; val lz = z - gz
        val dot = lx * ux + ly * uy + lz * uz
        val hx = lx - dot * ux; val hy = ly - dot * uy; val hz = lz - dot * uz
        // While the phone itself is turning, its readings say nothing about the car.
        if (twistAt > 0) { win.clear(); winLong = 0.0; winLat = 0.0; heldSince.clear(); return }
        sumX += hx; sumY += hy; sumZ += hz; sumN++
        if (!aligned) return
        val long = (hx * fx + hy * fy + hz * fz) / G
        // lateral axis = gravity x forward
        val sx = uy * fz - uz * fy; val sy = uz * fx - ux * fz; val sz = ux * fy - uy * fx
        val lat = (hx * sx + hy * sy + hz * sz) / G
        win.addLast(doubleArrayOf(long, lat)); winLong += long; winLat += lat
        if (win.size > 25) { val o = win.removeFirst(); winLong -= o[0]; winLat -= o[1] }
        val ml = winLong / win.size; val mt = winLat / win.size
        if (speed > 2.0) {
            hold(BRAKE, t, -ml >= BRAKE_G, -ml)
            hold(ACCEL, t, ml >= ACCEL_G, ml)
        }
        if (speed > 15 / 3.6) hold(TURN, t, abs(mt) >= TURN_G, abs(mt))
    }

    private fun remount(x: Double, y: Double, z: Double) {
        gx = x; gy = y; gz = z; refGx = x; refGy = y; refGz = z
        fx = 0.0; fy = 0.0; fz = 0.0; fVotes = 0
        sumX = 0.0; sumY = 0.0; sumZ = 0.0; sumN = 0
        win.clear(); winLong = 0.0; winLat = 0.0
    }

    /** The phone's own rotation (the part not about gravity: a corner doesn't count). A quick twist while moving = picked up. */
    fun gyro(t: Long, x: Double, y: Double, z: Double) {
        val dt = if (lastGyroAt == 0L) 0.0 else ((t - lastGyroAt) / 1000.0).coerceIn(0.0, 0.1)
        lastGyroAt = t
        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        val about = if (gn > 1e-6) (x * gx + y * gy + z * gz) / gn else 0.0
        val tilt = sqrt((x * x + y * y + z * z - about * about).coerceAtLeast(0.0))
        if (tilt > 0.5) { twistAt = t; twisted += tilt * dt }
        if (speed > MOVING_MS && tilt > 2.0) {
            if (gyroSince == 0L) gyroSince = t
            if (t - gyroSince >= 300L) flag(HANDLING, t, 0.0, 10_000L)
        } else gyroSince = 0L
    }

    fun gps(t: Long, speedMs: Double, lat: Double, lng: Double) {
        val dt = (t - lastGpsAt) / 1000.0
        if (!lastSpeed.isNaN() && dt in 0.5..3.0) {
            val dv = (speedMs - lastSpeed) / dt
            // Teach the forward axis: the horizontal push that matches the speed change.
            if (abs(dv) > 0.8 && sumN > 0) {
                val s = if (dv > 0) 1.0 else -1.0
                fx = fx * fVotes + s * sumX / sumN; fy = fy * fVotes + s * sumY / sumN; fz = fz * fVotes + s * sumZ / sumN
                val n = sqrt(fx * fx + fy * fy + fz * fz)
                if (n > 1e-6) { fx /= n; fy /= n; fz /= n; fVotes++; if (aligned) mounted = true }
            }
            // No mount yet (or the phone isn't mounted): hard braking and launches from GPS alone.
            if (!aligned && (speedMs > 2.0 || lastSpeed > 2.0)) {
                lastLat = lat; lastLng = lng
                if (dv <= -BRAKE_G * G) flag(BRAKE, t, -dv / G, 4_000L)
                if (dv >= ACCEL_G * G) flag(ACCEL, t, dv / G, 4_000L)
            }
        }
        sumX = 0.0; sumY = 0.0; sumZ = 0.0; sumN = 0
        lastSpeed = speedMs; speed = speedMs; lastGpsAt = t; lastLat = lat; lastLng = lng
        maxSpeed = max(maxSpeed, speedMs)
        speeding(t, speedMs)
    }

    private fun speeding(t: Long, v: Double) {
        recent.addLast(t to v)
        while (recent.isNotEmpty() && t - recent.first().first > 180_000L) recent.removeFirst()
        val mph = v * 2.23694
        val median = recent.map { it.second * 2.23694 }.sorted().let { it[it.size / 2] }
        val limit = LIMITS.firstOrNull { it >= median - 2 } ?: 70
        val over = mph > 80.0 || (recent.size >= 30 && mph > limit + 10)
        if (over) {
            if (speedingSince == 0L) speedingSince = t
            if (!speedingFlagged && t - speedingSince >= 10_000L) { speedingFlagged = true; add(SPEEDING, t, (mph * 10).roundToInt() / 10.0) }
        } else { speedingSince = 0L; speedingFlagged = false }
    }

    private fun hold(k: String, t: Long, over: Boolean, g: Double) {
        if (!over) { heldSince.remove(k); peak.remove(k); return }
        val since = heldSince.getOrPut(k) { t }
        peak[k] = max(peak[k] ?: 0.0, g)
        if (t - since >= HOLD_MS) { flag(k, t, peak[k] ?: g, 4_000L); heldSince.remove(k); peak.remove(k) }
    }

    private fun flag(k: String, t: Long, g: Double, refractory: Long) {
        if (t - (lastOf[k] ?: Long.MIN_VALUE / 2) < refractory) return
        lastOf[k] = t
        add(k, t, (g * 100).roundToInt() / 100.0)
    }

    private fun add(k: String, t: Long, value: Double) { events += DriveEvent(k, t, lastLat, lastLng, value) }

    private fun angle(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val n = sqrt(ax * ax + ay * ay + az * az) * sqrt(bx * bx + by * by + bz * bz)
        if (n < 1e-9) return 0.0
        return Math.toDegrees(acos(((ax * bx + ay * by + az * bz) / n).coerceIn(-1.0, 1.0)))
    }
}
