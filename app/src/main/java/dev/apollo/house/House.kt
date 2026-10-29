package dev.apollo.house

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.apollo.actions.ActionRegistry
import dev.apollo.net.Facts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * His home, for the whole process: one Google Home client. His taps on the Home screen go straight to [tap]; Athena's
 * `lights` / `fan` / `plug` / `home_state` actions come through ActionRegistry, after his approvals. Both refuse the
 * forbidden kinds. Every change goes to Athena as a `home` fact, and a snapshot (temperatures included) every 15 min.
 */
object House {
    @Volatile private var google: GoogleHome? = null
    private var lastSent = ""

    fun get(c: Context): GoogleHome = google ?: synchronized(this) {
        google ?: GoogleHome(c.applicationContext).also { g ->
            g.onChange = { s -> changed(c.applicationContext, s) }
            google = g
        }
    }

    /** From ApolloApp: the actions and the 15-minute snapshot. */
    fun init(c: Context) {
        ActionRegistry.handlers["lights"] = { ctx, a -> act(ctx, a, setOf(Kind.LIGHT)) }
        ActionRegistry.handlers["fan"] = { ctx, a -> act(ctx, a, setOf(Kind.FAN)) }
        ActionRegistry.handlers["plug"] = { ctx, a -> act(ctx, a, setOf(Kind.PLUG, Kind.SWITCH)) }
        ActionRegistry.handlers["home_state"] = { ctx, _ -> Facts.post(ctx, "home", snapshot(get(ctx).state.value)); true to "home state sent as a fact" }
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("home-snapshot", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SnapshotWorker>(15, TimeUnit.MINUTES).build())
    }

    /** From MainActivity.onCreate: Google's consent screen needs the activity before it starts. */
    fun attach(activity: ComponentActivity) {
        val g = get(activity)
        runCatching { g.register(activity) }
        g.start()
    }

    /** His own tap: no approvals, but still never a forbidden kind. */
    suspend fun tap(c: Context, deviceId: String, command: Command) {
        val g = get(c)
        val d = g.state.value.devices.firstOrNull { it.id == deviceId } ?: throw HouseError(HouseRules.linkWords(g.state.value.link).takeIf { g.state.value.link != Link.Connected }
            ?: "Google Home doesn't list that device right now")
        if (!HouseRules.allowed(d)) throw HouseError(HouseRules.FORBIDDEN)
        g.command(deviceId, command)
    }

    private fun act(c: Context, a: JSONObject, kinds: Set<Kind>): Pair<Boolean, String> {
        val g = get(c)
        g.start()
        val s = runBlocking { withTimeoutOrNull(15_000) { g.state.first { it.link != Link.Starting && (it.link != Link.Connected || it.devices.isNotEmpty()) } } }
            ?: return false to "Google Home didn't answer"
        if (s.link != Link.Connected) return false to HouseRules.linkWords(s.link)
        val name = a.optString("device").ifEmpty { a.optString("room") }
        val targets = HouseRules.targets(s, name, kinds, a.optString("device_id"))
        if (targets.isEmpty()) return false to "no ${kinds.joinToString("/") { it.word.lowercase() }} called \"$name\" (or it's one Apollo never controls)"
        val cmds = HouseRules.commands(a.optBoolean("on", true), a.optInt("brightness").takeIf { a.has("brightness") }, a.optInt("kelvin").takeIf { a.has("kelvin") },
            a.optInt("hue").takeIf { a.has("hue") }, a.optInt("saturation").takeIf { a.has("saturation") })
        val failed = mutableListOf<String>()
        runBlocking { for (d in targets) for (cmd in cmds) runCatching { g.command(d.id, cmd) }.onFailure { failed += "${d.name}: ${it.message}" } }
        return if (failed.isEmpty()) true to "done: ${targets.joinToString { it.name }}" else false to failed.joinToString("; ")
    }

    private fun changed(c: Context, s: Snapshot) {
        if (s.link != Link.Connected) return
        // Only real changes (state, not the timestamp) become facts.
        val key = s.devices.joinToString("|") { "${it.id}:${it.on}:${it.level}:${it.online}:${it.tempC}:${it.heatC}:${it.coolC}:${it.mode}" }
        if (key == lastSent) return
        lastSent = key
        Facts.post(c, "home", snapshot(s))
    }

    fun snapshot(s: Snapshot): JSONObject = JSONObject().put("link", HouseRules.linkWords(s.link)).put("devices", JSONArray().apply {
        s.devices.forEach { d ->
            put(JSONObject().put("id", d.id).put("name", d.name).put("kind", d.kind.word).put("room", s.room(d.roomId)?.name ?: "")
                .put("online", d.online).put("on", d.on).put("level", d.level).put("kelvin", d.kelvin).put("temp_c", d.tempC)
                .put("controllable", d.controllable).apply {
                    d.heatC?.let { put("heat_c", it) }; d.coolC?.let { put("cool_c", it) }; d.mode?.let { put("mode", it) }
                })
        }
    })
}

/** Every 15 minutes: the home as it is, temperatures included (a cold night with the fan on is his request). */
class SnapshotWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val g = House.get(applicationContext)
        g.start()
        val s = withTimeoutOrNull(20_000) { g.state.first { it.link != Link.Starting && (it.link != Link.Connected || it.devices.isNotEmpty()) } }
        if (s?.link == Link.Connected) Facts.post(applicationContext, "home", House.snapshot(s))
        return Result.success()
    }
}
