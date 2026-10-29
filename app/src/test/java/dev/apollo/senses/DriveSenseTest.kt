package dev.apollo.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Zeus's DriveTest on synthetic traces: the car's own forward and sideways push, turned into what a phone in an odd
 * mount reads (gravity included) at 50 Hz, with 1 Hz GPS.
 */
class DriveSenseTest {
    /** A phone mount: yaw, pitch and roll of the phone relative to the car, in degrees. */
    private class Mount(yaw: Double, pitch: Double, roll: Double) {
        private val m: Array<DoubleArray>
        init {
            val (a, b, c) = listOf(yaw, pitch, roll).map { Math.toRadians(it) }
            val rz = arrayOf(doubleArrayOf(cos(a), -sin(a), 0.0), doubleArrayOf(sin(a), cos(a), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
            val ry = arrayOf(doubleArrayOf(cos(b), 0.0, sin(b)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(b), 0.0, cos(b)))
            val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(c), -sin(c)), doubleArrayOf(0.0, sin(c), cos(c)))
            m = mul(mul(rz, ry), rx)
        }
        private fun mul(p: Array<DoubleArray>, q: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { p[i][it] * q[it][j] } } }
        /** Car frame (forward, left, up) to phone frame. */
        fun phone(v: DoubleArray) = DoubleArray(3) { i -> (0..2).sumOf { m[it][i] * v[it] } }
    }

    /** A drive as phases of (seconds, forward m/s², lateral m/s²). */
    private fun drive(a: DriveSense, mount: Mount, phases: List<Triple<Double, Double, Double>>): Long {
        var t = 1_000_000L
        var v = 0.0
        var lat = 42.45
        var nextGps = t
        for ((secs, fwd, side) in phases) {
            val end = t + (secs * 1000).toLong()
            while (t < end) {
                v = (v + fwd * 0.02).coerceAtLeast(0.0)
                val p = mount.phone(doubleArrayOf(fwd, side, DriveSense.G))
                a.accel(t, p[0], p[1], p[2])
                val yaw = mount.phone(doubleArrayOf(0.0, 0.0, if (v > 1) side / v else 0.0))
                a.gyro(t, yaw[0], yaw[1], yaw[2])
                if (t >= nextGps) { a.gps(t, v, lat, -71.08); lat += v / 111_320.0; nextGps += 1000 }
                t += 20
            }
        }
        return t
    }

    private val calm = listOf(
        Triple(5.0, 0.0, 0.0),        // parked, phone settles
        Triple(10.0, 1.5, 0.0),       // pulling away gently (0.15 g)
        Triple(30.0, 0.0, 0.0),       // cruising at 15 m/s
        Triple(6.0, -1.2, 0.0),       // easing off
        Triple(20.0, 0.0, 0.0),
    )

    private fun kinds(a: DriveSense) = a.events.map { it.kind }

    @Test fun aGentleDriveHasNoEvents() {
        val a = DriveSense()
        drive(a, Mount(37.0, -62.0, 12.0), calm)
        assertTrue(a.events.toString(), a.events.isEmpty())
        assertTrue(a.mounted)
        assertEquals(15.0, a.maxSpeed, 0.1)
    }

    @Test fun hardBrakingAndASharpTurnInAnyMount() {
        for (mount in listOf(Mount(0.0, 0.0, 0.0), Mount(90.0, -70.0, 0.0), Mount(-140.0, 25.0, 80.0))) {
            val a = DriveSense()
            drive(a, mount, calm.take(3) + listOf(Triple(1.5, -4.2, 0.0), Triple(5.0, 0.0, 0.0), Triple(2.0, 0.0, 4.0), Triple(10.0, 0.0, 0.0)))
            assertEquals(a.events.toString(), listOf(DriveSense.BRAKE, DriveSense.TURN), kinds(a))
            assertTrue(a.events[0].value >= 0.3 && a.events[0].lat > 42.45)
        }
    }

    @Test fun rapidAcceleration() {
        val a = DriveSense()
        drive(a, Mount(20.0, -50.0, 5.0), calm.take(3) + listOf(Triple(2.0, -1.0, 0.0), Triple(2.0, 3.6, 0.0), Triple(10.0, 0.0, 0.0)))
        assertEquals(listOf(DriveSense.ACCEL), kinds(a))
    }

    @Test fun withoutAMountGpsStillCatchesHardBraking() {
        val a = DriveSense()
        var t = 0L
        for (v in listOf(0.0, 2.5, 5.0, 7.5, 10.0, 12.5, 14.0, 14.0, 14.0, 10.0, 6.0, 5.0, 5.0)) { a.gps(t, v, 42.45, -71.08); t += 1000 }
        assertEquals(listOf(DriveSense.BRAKE), kinds(a))
        assertFalse(a.mounted)
    }

    @Test fun pickingThePhoneUpWhileMoving() {
        val a = DriveSense()
        val mount = Mount(0.0, -60.0, 0.0)
        var t = drive(a, mount, calm.take(3))
        val up = mount.phone(doubleArrayOf(0.0, 0.0, 1.0))
        val twist = doubleArrayOf(0.0, up[2], -up[1]).let { v -> val n = Math.sqrt(v.sumOf { it * it }); DoubleArray(3) { v[it] / n * 2.6 } }
        val lifted = Mount(10.0, -10.0, 30.0)
        repeat(50) { a.gyro(t, twist[0], twist[1], twist[2]); val p = lifted.phone(doubleArrayOf(0.0, 0.0, DriveSense.G)); a.accel(t, p[0], p[1], p[2]); t += 20 }
        repeat(300) { a.gyro(t, 0.0, 0.0, 0.0); val p = lifted.phone(doubleArrayOf(0.0, 0.0, DriveSense.G)); a.accel(t, p[0], p[1], p[2]); if (it % 50 == 0) a.gps(t, 15.0, 42.46, -71.08); t += 20 }
        // Only the pick-up: the tilt itself is not read as braking or a turn.
        assertEquals(a.events.toString(), listOf(DriveSense.HANDLING), kinds(a))
    }

    @Test fun speedingIsOneEventPerStretch() {
        val a = DriveSense()
        var t = 0L
        repeat(60) { a.gps(t, 29.0, 42.45, -71.08); t += 1000 }   // 65 mph for a minute
        repeat(20) { a.gps(t, 37.0, 42.45, -71.08); t += 1000 }   // 83 mph for 20 s
        repeat(20) { a.gps(t, 29.0, 42.45, -71.08); t += 1000 }
        repeat(15) { a.gps(t, 37.5, 42.45, -71.08); t += 1000 }
        val speeding = a.events.filter { it.kind == DriveSense.SPEEDING }
        assertEquals(2, speeding.size)
        assertEquals(83.0, speeding[0].value, 1.0)
        assertEquals(83.9, a.maxSpeed * 2.23694, 0.1)
    }
}
