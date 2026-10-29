package dev.apollo.clio

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.apollo.calypso.SealedSecret
import dev.apollo.net.Server
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Clio to the server once a day (Zeus's ClioShip; Owner, 2026-10-21: "logs go to VPS storage"), for Athena's
 * `phone_logs` tool. New lines of every day file go up, Zeus's old ones too, each file from where the last upload
 * stopped. Calypso's areas and anything key-shaped never leave. The phone's own log stays as it is.
 */
object ClioShip {
    const val LINES = 1000
    val SEALED = setOf("calypso", "vault", "keys", "seal", "janus", "plazmi")
    private val SECRET = Regex(
        """sk-ant-|\bgh[pousr]_[A-Za-z0-9]{20,}|github_pat_|whsec_|AIza[0-9A-Za-z_-]{30,}|-----BEGIN|\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|[A-Za-z0-9+/_-]{40,}""",
    )

    /** False for a Calypso line and one that carries something key-shaped. Pure. */
    fun keep(l: ClioLine): Boolean = l.area.lowercase() !in SEALED && !l.event.contains("calypso", true) && !l.detail.contains("calypso", true) &&
        !SECRET.containsMatchIn(l.event) && !SECRET.containsMatchIn(l.detail)

    /**
     * Sends what each file holds past its cursor ([sent]: file name -> lines already read), [LINES] at a time. [send]
     * throws on failure; a failure keeps the rest for next time. Returns the new cursors and how many lines went. Pure.
     */
    fun ship(files: List<File>, sent: Map<String, Int>, send: (JSONArray) -> Unit): Pair<Map<String, Int>, Int> {
        val cur = sent.toMutableMap()
        var n = 0
        for (f in files) {
            val raw = runCatching { f.readLines() }.getOrDefault(emptyList())
            var from = cur[f.name] ?: 0
            while (from < raw.size) {
                val chunk = raw.subList(from, minOf(raw.size, from + LINES))
                val out = JSONArray()
                chunk.mapNotNull { runCatching { ClioLog.line(JSONObject(it)) }.getOrNull() }.filter(::keep).forEach { l ->
                    out.put(JSONObject().put("at", l.at).put("area", l.area).put("level", l.level).put("event", l.event).put("detail", l.detail))
                }
                if (out.length() > 0) runCatching { send(out) }.onFailure { return cur to n }
                from += chunk.size
                cur[f.name] = from
                n += out.length()
            }
        }
        return cur to n
    }

    private fun prefs(c: Context) = c.getSharedPreferences("clio-ship", Context.MODE_PRIVATE)

    /** Blocking, off the main thread. Only once paired. */
    @Synchronized fun tick(c: Context) {
        if (!SealedSecret(c, Server.TOKEN_SLOT).has()) return
        val p = prefs(c)
        val files = ClioLog(File(c.filesDir, "clio")).files()
        val sent = files.associate { it.name to p.getInt("f:" + it.name, 0) }.filterValues { it > 0 }
        val (cur, n) = ship(files, sent) { Server(c).postClio(it) }
        p.edit().apply {
            p.all.keys.filter { it.startsWith("f:") && it.removePrefix("f:") !in cur }.forEach { remove(it) }   // files gone from the phone
            cur.forEach { (name, line) -> putInt("f:$name", line) }
            if (n > 0) putLong("sent.at", System.currentTimeMillis())
        }.apply()
        if (n > 0) Clio.info("clio", "logs sent", "$n lines")
    }

    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("apollo-clio-ship", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ClioShipWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

class ClioShipWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = runCatching { ClioShip.tick(applicationContext) }.fold({ Result.success() }, { Result.retry() })
}
