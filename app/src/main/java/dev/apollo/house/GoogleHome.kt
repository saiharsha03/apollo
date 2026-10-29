package dev.apollo.house

import android.content.Context
import androidx.activity.ComponentActivity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.home.ConnectivityState
import com.google.home.DeviceType
import com.google.home.DeviceTypeFactory
import com.google.home.FactoryRegistry
import com.google.home.ForcePermissionFlow
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeException
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import com.google.home.google.GoogleCameraDevice
import com.google.home.google.GoogleDoorbellDevice
import com.google.home.google.GoogleGarageDevice
import com.google.home.google.GoogleGateDevice
import com.google.home.google.GoogleSecuritySystemDevice
import com.google.home.google.GoogleTVDevice
import com.google.home.matter.standard.AudioDoorbellDevice
import com.google.home.matter.standard.CameraDevice
import com.google.home.matter.standard.ColorControl
import com.google.home.matter.standard.ColorControlTrait
import com.google.home.matter.standard.ColorTemperatureLightDevice
import com.google.home.matter.standard.ContactSensorDevice
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.DimmablePlugInUnitDevice
import com.google.home.matter.standard.DimmerSwitchDevice
import com.google.home.matter.standard.DoorLock
import com.google.home.matter.standard.DoorLockDevice
import com.google.home.matter.standard.DoorbellDevice
import com.google.home.matter.standard.ExtendedColorLightDevice
import com.google.home.matter.standard.FanDevice
import com.google.home.matter.standard.LevelControl
import com.google.home.matter.standard.LevelControlTrait
import com.google.home.matter.standard.OccupancySensorDevice
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffLightSwitchDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import com.google.home.matter.standard.SpeakerDevice
import com.google.home.matter.standard.TemperatureMeasurement
import com.google.home.matter.standard.TemperatureSensorDevice
import com.google.home.matter.standard.Thermostat
import com.google.home.matter.standard.ThermostatDevice
import com.google.home.matter.standard.VideoDoorbellDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import com.google.home.HomeDevice as SdkDevice

/**
 * The Google Home APIs for Android (Device and Structure APIs; no commissioning).
 * Everything goes through Google Play services, another process, so nothing here opens a socket of its own.
 * One HomeClient per process, as Google requires.
 */
class GoogleHome(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Null when Play services is missing or too old; the reason is then on [state]. */
    private val client: HomeClient? by lazy {
        val gms = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(app, MIN_GMS)
        if (gms != ConnectionResult.SUCCESS) {
            _state.value = Snapshot(Link.NotConnected("Google Play services is missing or older than the Home APIs need (update it from the Play Store)"))
            null
        } else runCatching {
            Home.getClient(app, HomeConfig(coroutineContext = Dispatchers.IO, factoryRegistry = REGISTRY))
        }.getOrElse {
            _state.value = Snapshot(Link.NotConnected("The Home APIs didn't start: ${it.message ?: it.javaClass.simpleName}"))
            null
        }
    }

    /** The SDK's devices by id, for commands. */
    private val sdkDevices = ConcurrentHashMap<String, SdkDevice>()
    private val deviceJobs = ConcurrentHashMap<String, Job>()
    private val reads = ConcurrentHashMap<String, Device>()

    /** Called after every change (House turns changes into facts for Athena). */
    @Volatile var onChange: (Snapshot) -> Unit = {}
    private var main: Job? = null

    /** Must run in the activity's onCreate, before it is started: the consent screen is an activity result. */
    fun register(activity: ComponentActivity) {
        client?.registerActivityResultCallerForPermissions(activity)
    }

    @Synchronized fun start() {
        val c = client ?: return
        if (main?.isActive == true) return
        main = scope.launch {
            c.hasPermissions().collect { p ->
                when (p) {
                    PermissionsState.GRANTED -> { _state.update { it.copy(link = Link.Connected) }; subscribe(c) }
                    PermissionsState.NOT_GRANTED -> { stopDevices(); _state.value = Snapshot(Link.NeedsPermission) }
                    PermissionsState.PERMISSIONS_STATE_UNAVAILABLE ->
                        _state.value = Snapshot(Link.NotConnected("Google Home permissions aren't available for Apollo on this phone. The app may not be registered in the Google Home Developer Console yet, or your account isn't a test user"))
                    PermissionsState.PERMISSIONS_STATE_UNINITIALIZED -> Unit
                }
            }
        }
    }

    suspend fun requestPermission(): String? {
        val c = client ?: return HouseRules.linkWords(_state.value.link)
        return try {
            val r = c.requestPermissions(ForcePermissionFlow.FORCE_LAUNCH)
            when (r.status) {
                PermissionsResultStatus.SUCCESS -> null
                PermissionsResultStatus.CANCELLED -> "Cancelled"
                PermissionsResultStatus.ERROR -> r.errorMessage ?: "Google refused the request"
            }
        } catch (e: HomeException) { e.message ?: "Google refused the request" }
    }

    /** Parent of every subscription below, so a revoked grant stops them all. */
    private var subs: Job? = null

    @Synchronized private fun subscribe(c: HomeClient) {
        if (subs?.isActive == true) return
        subs = scope.launch { subscriptions(c) }
    }

    private fun CoroutineScope.subscriptions(c: HomeClient) {
        launch {
            c.rooms().collect { set -> _state.update { it.copy(rooms = set.map { r -> Room(r.id.id, r.name) }.sortedBy { r -> r.name }) } }
        }
        launch {
            c.devices().collect { set ->
                val ids = set.map { it.id.id }.toSet()
                for (gone in deviceJobs.keys - ids) { deviceJobs.remove(gone)?.cancel(); sdkDevices.remove(gone); reads.remove(gone) }
                // The whole list first, so HomeNews waits for every device's read, not just the ones started so far.
                for (d in set) sdkDevices[d.id.id] = d
                for (d in set) {
                    val id = d.id.id
                    if (deviceJobs[id]?.isActive == true) continue
                    deviceJobs[id] = launch {
                        d.types().collect { types ->
                            reads[id] = read(d, types)
                            publish()
                        }
                    }
                }
                publish()
            }
        }
    }

    private fun publish() {
        _state.update { it.copy(devices = reads.values.sortedBy { d -> d.name.lowercase() }, at = System.currentTimeMillis()) }
        onChange(_state.value)
    }

    @Synchronized private fun stopDevices() {
        subs?.cancel(); subs = null
        deviceJobs.clear(); sdkDevices.clear(); reads.clear()
    }

    suspend fun command(deviceId: String, command: Command) {
        val d = sdkDevices[deviceId] ?: throw HouseError("That device isn't in Google Home any more")
        val current = reads[deviceId]
        if (!HouseRules.allowed(current)) throw HouseError(HouseRules.FORBIDDEN)
        val type = withTimeout(10_000) { primary(d.types().first()) } ?: throw HouseError("${d.name} reports no device type")
        try {
            withTimeout(15_000) {
                when (command) {
                    is Command.Power -> type.trait(OnOff)?.let { if (command.on) it.on() else it.off() } ?: throw HouseError("${d.name} can't switch")
                    is Command.Brightness -> type.trait(LevelControl)?.moveToLevelWithOnOff(
                        HouseRules.toLevel(command.percent).toUByte(), null, LevelControlTrait.OptionsBitmap(), LevelControlTrait.OptionsBitmap(),
                    ) ?: throw HouseError("${d.name} can't dim")
                    is Command.ColorTemp -> type.trait(ColorControl)?.moveToColorTemperature(
                        HouseRules.toMireds(command.kelvin).toUShort(), 0u.toUShort(), ColorControlTrait.OptionsBitmap(), ColorControlTrait.OptionsBitmap(),
                    ) ?: throw HouseError("${d.name} has no white temperature")
                    is Command.Color -> type.trait(ColorControl)?.moveToHueAndSaturation(
                        HouseRules.toMatterHue(command.hue).toUByte(), HouseRules.toMatterSat(command.saturation).toUByte(), 0u.toUShort(),
                        ColorControlTrait.OptionsBitmap(), ColorControlTrait.OptionsBitmap(),
                    ) ?: throw HouseError("${d.name} isn't a color light")
                }
            }
        } catch (e: HomeException) {
            throw HouseError(e.message ?: "Google Home refused the command")
        }
    }

    companion object {
        /** Home APIs 1.11 needs Google Play services 26.36.25 or later (release notes, 2026-09-23). */
        const val MIN_GMS = 263_625_000

        /** Device types in the order Apollo prefers when a device reports several (a color bulb is also dimmable). */
        private val KINDS: List<Pair<DeviceTypeFactory<out DeviceType>, Kind>> = listOf(
            DoorLockDevice to Kind.LOCK, GoogleGarageDevice to Kind.GARAGE, GoogleGateDevice to Kind.GARAGE,
            GoogleSecuritySystemDevice to Kind.SECURITY, GoogleCameraDevice to Kind.CAMERA, CameraDevice to Kind.CAMERA,
            GoogleDoorbellDevice to Kind.DOORBELL, VideoDoorbellDevice to Kind.DOORBELL, DoorbellDevice to Kind.DOORBELL,
            AudioDoorbellDevice to Kind.DOORBELL,
            ExtendedColorLightDevice to Kind.LIGHT, ColorTemperatureLightDevice to Kind.LIGHT, DimmableLightDevice to Kind.LIGHT,
            OnOffLightDevice to Kind.LIGHT,
            DimmablePlugInUnitDevice to Kind.PLUG, OnOffPluginUnitDevice to Kind.PLUG,
            DimmerSwitchDevice to Kind.SWITCH, OnOffLightSwitchDevice to Kind.SWITCH,
            FanDevice to Kind.FAN, ThermostatDevice to Kind.THERMOSTAT, SpeakerDevice to Kind.SPEAKER, GoogleTVDevice to Kind.TV,
            ContactSensorDevice to Kind.SENSOR, OccupancySensorDevice to Kind.SENSOR, TemperatureSensorDevice to Kind.SENSOR,
        )
        private val ORDER = KINDS.map { it.first }

        val REGISTRY = FactoryRegistry(
            traits = listOf(OnOff, LevelControl, ColorControl, DoorLock, TemperatureMeasurement, Thermostat),
            types = ORDER,
        )

        private fun primary(types: Set<DeviceType>): DeviceType? =
            types.filter { it.factory in ORDER }.minByOrNull { ORDER.indexOf(it.factory) }

        /** One SDK device to Apollo's plain data. Anything with a lock trait counts as a lock. */
        private fun read(d: SdkDevice, types: Set<DeviceType>): Device {
            val t = primary(types)
            val lock = types.any { it.trait(DoorLock) != null }
            val kind = if (lock) Kind.LOCK else KINDS.firstOrNull { it.first == t?.factory }?.second ?: Kind.OTHER
            val onOff = t?.trait(OnOff)
            val level = t?.trait(LevelControl)
            val color = t?.trait(ColorControl)
            val caps = color?.colorCapabilities
            val conn = (t?.metadata?.sourceConnectivity ?: d.sourceConnectivity).connectivityState
            val thermostat = types.firstNotNullOfOrNull { it.trait(Thermostat) }
            val traits = buildSet {
                if (onOff != null) add(Trait.ON_OFF)
                if (level != null) add(Trait.LEVEL)
                if (color != null && caps?.colorTemperature != false) add(Trait.COLOR_TEMP)
                if (color != null && caps?.hueSaturation == true) add(Trait.COLOR)
            }
            return Device(
                id = d.id.id, name = d.name, kind = kind, roomId = d.roomId?.id,
                online = conn == ConnectivityState.ONLINE || conn == ConnectivityState.PARTIALLY_ONLINE,
                traits = traits,
                on = onOff?.onOff,
                level = level?.currentLevel?.toInt()?.let { HouseRules.toPercent(it) },
                kelvin = color?.colorTemperatureMireds?.toInt()?.takeIf { it > 0 }?.let { HouseRules.toKelvin(it) },
                hue = color?.currentHue?.toInt()?.let { HouseRules.toDegrees(it) },
                saturation = color?.currentSaturation?.toInt()?.let { HouseRules.satPercent(it) },
                // Matter reports hundredths of a degree C; any type of the device may carry the sensor.
                tempC = types.firstNotNullOfOrNull { it.trait(TemperatureMeasurement)?.measuredValue }?.let { it / 100.0 }
                    ?: thermostat?.localTemperature?.let { it / 100.0 },
                // A thermostat's set points and mode, read only (Apollo never changes a thermostat).
                heatC = thermostat?.occupiedHeatingSetpoint?.let { it / 100.0 },
                coolC = thermostat?.occupiedCoolingSetpoint?.let { it / 100.0 },
                mode = thermostat?.systemMode?.toString()?.lowercase(),
            )
        }
    }
}
