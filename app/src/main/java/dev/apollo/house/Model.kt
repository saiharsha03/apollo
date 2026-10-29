package dev.apollo.house

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

/*
 * His lights, plugs and fans. AiDot (Linkind and similar) has no public API, so the devices are linked into the Google
 * Home app and Apollo reaches them through the Google Home APIs for Android (Zeus's docs/HESTIA.md). Everything here is
 * plain data, so the screen, the actions and the tests never see the SDK.
 */

/**
 * What a device is. `forbidden` kinds (locks, garage doors and gates, security systems, cameras, doorbells) are never
 * controlled by Apollo at all: not from a tap, not by Athena, whatever the approvals say.
 */
enum class Kind(val word: String, val forbidden: Boolean = false) {
    LIGHT("Light"), PLUG("Plug"), SWITCH("Switch"), FAN("Fan"), THERMOSTAT("Thermostat"), SPEAKER("Speaker"), TV("TV"), SENSOR("Sensor"),
    LOCK("Lock", true), GARAGE("Garage door", true), SECURITY("Security system", true), CAMERA("Camera", true), DOORBELL("Doorbell", true),
    OTHER("Device");

    companion object {
        /** Kinds Apollo sends commands to. Thermostats, speakers and TVs stay with the Google Home app. */
        val controllable = setOf(LIGHT, PLUG, SWITCH, FAN)
    }
}

enum class Trait { ON_OFF, LEVEL, COLOR_TEMP, COLOR }

@Immutable data class Room(val id: String, val name: String)

/** `level` a percent (1-100), `kelvin` the white temperature, `hue` 0-359 and `saturation` 0-100; null when not reported. */
@Immutable
data class Device(
    val id: String, val name: String, val kind: Kind, val roomId: String?, val online: Boolean = true, val traits: Set<Trait> = emptySet(),
    val on: Boolean? = null, val level: Int? = null, val kelvin: Int? = null, val hue: Int? = null, val saturation: Int? = null,
    /** A temperature sensor's reading in degrees C, read only. */
    val tempC: Double? = null,
    /** A thermostat's heating and cooling set points (degrees C) and its mode (heat, cool, auto, off...), read only. */
    val heatC: Double? = null, val coolC: Double? = null, val mode: String? = null,
) {
    val controllable: Boolean get() = !kind.forbidden && kind in Kind.controllable && Trait.ON_OFF in traits
}

sealed class Link {
    data object Starting : Link()
    data object NeedsPermission : Link()
    data object Connected : Link()
    data class NotConnected(val reason: String) : Link()
}

@Immutable
data class Snapshot(val link: Link = Link.Starting, val rooms: List<Room> = emptyList(), val devices: List<Device> = emptyList(), val at: Long = 0L) {
    fun room(id: String?): Room? = id?.let { r -> rooms.firstOrNull { it.id == r } }
}

sealed class Command {
    data class Power(val on: Boolean) : Command()
    data class Brightness(val percent: Int) : Command()
    data class ColorTemp(val kelvin: Int) : Command()
    data class Color(val hue: Int, val saturation: Int) : Command()
}

class HouseError(message: String) : Exception(message)

/** The pure rules: the hard block, finding devices by name or room, and Matter unit conversions. */
object HouseRules {
    const val FORBIDDEN = "Apollo never controls locks, garage doors, gates, cameras, doorbells or security systems. Use the Google Home app for those."

    /** The hard block, checked before every command whoever sent it. */
    fun allowed(d: Device?): Boolean = d != null && d.controllable

    /**
     * The devices a request names: a device by name or id, else every controllable device in a named room. Kinds filter.
     * A [deviceId] (a Home tile's tap) is that one device or none: two lamps may share a name.
     */
    fun targets(s: Snapshot, name: String, kinds: Set<Kind>, deviceId: String = ""): List<Device> {
        if (deviceId.isNotEmpty()) return s.devices.filter { it.id == deviceId && it.kind in kinds && allowed(it) }
        val q = name.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val byDevice = s.devices.filter { (it.id == name || it.name.lowercase() == q) && it.kind in kinds }
        if (byDevice.isNotEmpty()) return byDevice.filter { allowed(it) }
        val room = s.rooms.firstOrNull { it.name.lowercase() == q } ?: return s.devices.filter { it.name.lowercase().contains(q) && it.kind in kinds && allowed(it) }
        return s.devices.filter { it.roomId == room.id && it.kind in kinds && allowed(it) }
    }

    /** One `lights` / `fan` / `plug` request to commands, in the order they go out. */
    fun commands(on: Boolean, brightness: Int?, kelvin: Int?, hue: Int?, saturation: Int?): List<Command> = buildList {
        add(Command.Power(on))
        if (on) {
            brightness?.let { add(Command.Brightness(it.coerceIn(1, 100))) }
            kelvin?.let { add(Command.ColorTemp(it)) }
            if (hue != null && saturation != null) add(Command.Color(hue, saturation))
        }
    }

    fun toLevel(percent: Int): Int = (percent.coerceIn(1, 100) * 254 / 100.0).roundToInt().coerceIn(1, 254)
    fun toPercent(level: Int): Int = (level.coerceIn(0, 254) * 100 / 254.0).roundToInt().coerceIn(if (level > 0) 1 else 0, 100)
    fun toMireds(kelvin: Int): Int = (1_000_000.0 / kelvin.coerceIn(1000, 10_000)).roundToInt()
    fun toKelvin(mireds: Int): Int = if (mireds <= 0) 0 else ((1_000_000.0 / mireds) / 10).roundToInt() * 10
    fun toMatterHue(degrees: Int): Int = (degrees.mod(360) * 254 / 360.0).roundToInt().coerceIn(0, 254)
    fun toMatterSat(percent: Int): Int = (percent.coerceIn(0, 100) * 254 / 100.0).roundToInt()
    fun toDegrees(hue: Int): Int = (hue.coerceIn(0, 254) * 360 / 254.0).roundToInt() % 360
    fun satPercent(sat: Int): Int = (sat.coerceIn(0, 254) * 100 / 254.0).roundToInt()

    fun linkWords(l: Link): String = when (l) {
        Link.Starting -> "Connecting to Google Home"
        Link.NeedsPermission -> "Apollo doesn't have access to your Google Home yet"
        Link.Connected -> "Connected"
        is Link.NotConnected -> l.reason
    }
}
