package dev.apollo.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Where an outbox item goes on the phone (docs/API.md, outbox kinds). */
enum class Route { CARD, CARD_REMOVE, NOTIFY, MESSAGE, APPS, ACTION, RULE, CALL, SETTING, ACTIVITY, CHAT, UNKNOWN }

object Outbox {
    /** Pure: the outbox kind to its handler. An unknown kind is kept unread and acked, so a newer server can't wedge sync. */
    fun route(kind: String): Route = when (kind) {
        "card" -> Route.CARD
        "card_remove" -> Route.CARD_REMOVE
        "notify" -> Route.NOTIFY
        "message" -> Route.MESSAGE
        "apps" -> Route.APPS
        "action" -> Route.ACTION
        "rule" -> Route.RULE
        "setting" -> Route.SETTING
        "call" -> Route.CALL
        "activity" -> Route.ACTIVITY
        "reaction", "edit", "delete", "typing" -> Route.CHAT
        else -> Route.UNKNOWN
    }

    /** Pure: a mini-app path from the manifest is safe to write under filesDir/apps only if it stays inside it. */
    fun safePath(app: String, path: String): Boolean =
        Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches(app) && path.isNotEmpty() && !path.startsWith("/") && !path.contains('\\') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** Saved before an action runs, so a sync that dies part way never runs it a second time. */
    const val RUNNING = "running"

    /** Pure: an action's saved mark as (ok, detail) to report again without running it; null when it never started. */
    fun done(mark: String?): Pair<Boolean, String>? = when {
        mark == null -> null
        mark == RUNNING -> false to "stopped part way through; not run again"
        else -> mark.startsWith("1|") to mark.substringAfter('|')
    }

    /** Pure: an action id the server gave (its outbox id); the phone's own asks (hermes-<ms>) have nothing to report to. */
    fun fromServer(id: String): Boolean = id.isNotEmpty() && id.all { it.isDigit() }

    /** Pure: a failure the whole sync retries (the network), not one item's bad data. */
    fun retryable(e: Throwable): Boolean = e is java.io.IOException

    /**
     * Pure: what the server refused stays queued unless it never will take it (400, 413, 422). Any other 4xx (a 404 or
     * 421 while Caddy or the server restarts, 408, 429) is the way there, not the data: kept and retried.
     */
    fun keepReport(code: Int): Boolean = code !in setOf(400, 413, 422)

    /** Pure: the mark kept once it ran. */
    fun mark(ok: Boolean, detail: String): String = (if (ok) "1|" else "0|") + detail
}

/**
 * Runs an outbox action Athena asked for. Nothing is wired yet: the approvals table and the native actions arrive with
 * their features. [run] returns (ok, detail) and the result goes back to the server.
 */
fun interface Actions {
    fun run(c: Context, id: String, action: String, args: JSONObject): Pair<Boolean, String>

    companion object {
        @Volatile var current: Actions = Actions { _, _, action, _ -> false to "not wired yet: $action" }
    }
}

/** Pulls the server's outbox, files each item, acks, and keeps the mini-app folder in step. Blocking: call on IO. */
/** The server's mini apps, mirrored one way. Not Zeus's old "apps" folder: that one moves to the server (ZeusApps). */
const val APPS_DIR = "apollo-apps"

object Sync {
    private const val CURSOR = "sync.cursor"
    /** phone_setting names that are words, not numbers: the theme, his nav order and his Home pins (prefs apollo_ui). */
    val UI_SETTINGS = setOf("theme", "nav_order", "home_pins")
    /** phone_setting names that are numbers: only what the senses read (SleepWatch; server tools.SENSE_SETTINGS). */
    val SENSE_SETTINGS = Regex("night_(start|end)_h(_(mon|tue|wed|thu|fri|sat|sun))?|watch_idle_min|reask_min|answer_min|still_idle_min|wake_confirm_min|alarm_missed_min")
    private const val APPS_VERSION = "apps.version"
    private const val WORK_NOW = "sync.now"
    private const val WORK_PERIODIC = "sync.periodic"

    private const val REPORT = "report."

    /**
     * His final answer to an ask (run, refused, said no): it becomes the action's mark, so a re-filed item reports it
     * and not "waiting for Owner", and waits in the queue until the server has it (offline, the next sync sends it).
     */
    fun finalResult(c: Context, id: String, ok: Boolean, detail: String) {
        val db = LocalDb.get(c)
        db.put("action.$id", Outbox.mark(ok, detail))
        db.put(REPORT + id, Outbox.mark(ok, detail))
        now(c)
    }

    /** The queued final results, each removed once the server took it; the network failing keeps the rest for later. */
    private fun report(db: LocalDb, server: Server) {
        for (k in db.keys(REPORT)) {
            val (ok, detail) = Outbox.done(db.get(k)) ?: continue
            try { server.actionResult(k.removePrefix(REPORT), ok, detail) } catch (e: ServerError) { if (Outbox.keepReport(e.code)) throw e }
            db.remove(k)
        }
    }

    /** One sync at a time: the periodic and the immediate worker would otherwise file (and run) the same items together. */
    fun run(c: Context) = synchronized(this) {
        val db = LocalDb.get(c)
        val server = Server(c)
        report(db, server)
        while (true) {
            val since = db.get(CURSOR)?.toLongOrNull() ?: 0L
            val r = server.sync(since)
            val items = r.optJSONArray("outbox")
            val acked = mutableListOf<String>()
            var appsChanged = false
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items!!.getJSONObject(i)
                val id = item.get("id").toString()
                val data = item.optJSONObject("data") ?: JSONObject()
                try { when (Outbox.route(item.optString("kind"))) {
                    // A card with its own id is a change to that card (state kept: one he swiped away stays away).
                    Route.CARD -> db.putCard(Card(data.optString("id").ifEmpty { id }, data.optString("title"), data.optString("body"), dev.apollo.home.CardActions.buttons(data.optJSONArray("actions")),
                        data.optString("app").ifEmpty { null }, System.currentTimeMillis(), "open",
                        data.optLong("until").takeIf { it > 0 }, data.optLong("countdown").takeIf { it > 0 }), keepState = data.has("id"))
                    Route.CARD_REMOVE -> db.removeCard(data.optString("id"))
                    // Now, at a time, repeating, with a tap target and her unseen check (actions/Notices.kt).
                    Route.NOTIFY -> dev.apollo.actions.Notices.fromOutbox(c, id, data)
                    Route.MESSAGE -> {
                        val from = data.optString("from", "athena")
                        if (from == "athena") dev.apollo.ui.AthenaNow.replyAt.value = System.currentTimeMillis()
                        if (from == "iris") dev.apollo.ui.AthenaNow.irisAt.value = System.currentTimeMillis()
                        db.putMessage(ChatMessage(data.optString("id", id), data.get("thread").toString(), from, data.optString("text"), data.optLong("at", System.currentTimeMillis()),
                            data.optJSONArray("choices")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
                            replyTo = data.opt("reply_to")?.takeIf { it != JSONObject.NULL }?.toString()),
                            data.optString("title", "Chat"), data.optString("agent").ifEmpty { null }, kind = data.optString("kind").ifEmpty { null }, fresh = true)
                        dev.apollo.chat.ChatNow.spoke(data.get("thread").toString(), from)
                    }
                    Route.CHAT -> dev.apollo.chat.ChatNow.apply(db, item.optString("kind"), data)
                    Route.APPS -> { appsChanged = true; dev.apollo.ui.AthenaNow.appsAt.value = System.currentTimeMillis() }
                    Route.ACTION -> {
                        // Run once per outbox id; a failed report or ack only sends the saved result again.
                        val key = "action.$id"
                        val (ok, detail) = Outbox.done(db.get(key)) ?: kotlin.run {
                            db.putInbox(id, "action", data.toString(), System.currentTimeMillis())
                            db.put(key, Outbox.RUNNING)
                            val refused = if (data.optBoolean("facts_turn")) dev.apollo.actions.PhoneActions.factsRefusal(c, data.optString("action")) else null
                            val out = if (refused != null) false to refused
                                else runCatching { Actions.current.run(c, id, data.optString("action"), data.optJSONObject("args") ?: JSONObject()) }.getOrElse { false to it.javaClass.simpleName }
                            // His answer may already have landed (a quick tap): its final mark stays.
                            if (db.get(key) == Outbox.RUNNING) db.put(key, Outbox.mark(out.first, out.second))
                            db.setInboxResult(id, out.second)
                            Outbox.done(db.get(key)) ?: out
                        }
                        // A refusal the server will never take (a 4xx) is dropped here, so it can't hold up the queue.
                        try { server.actionResult(id, ok, detail) } catch (e: ServerError) { if (Outbox.keepReport(e.code)) throw e }
                    }
                    Route.RULE -> {
                        db.putInbox(id, "rule", data.toString(), System.currentTimeMillis())
                        // Athena's add_rule: only ever stricter (Approvals refuses a loosening).
                        dev.apollo.approvals.Level.of(data.optString("level"))?.let { lvl ->
                            dev.apollo.approvals.Approvals(dev.apollo.approvals.ApolloDb.get(c)).addStricter(dev.apollo.actions.ActionRegistry.key(data.optString("action")), lvl, data.optString("from", "athena"))
                        }
                    }
                    Route.SETTING -> {
                        // A number the senses read (sleep windows and minutes); names only, never anything else.
                        val name = data.optString("name")
                        if (name in UI_SETTINGS) c.getSharedPreferences("apollo_ui", android.content.Context.MODE_PRIVATE).edit().putString(name, data.optString("value").take(1000)).commit()
                        if (name == "home_pins") dev.apollo.home.HomePins.changed.value++
                        if (name == "theme") dev.apollo.ui.Themes.load(c)
                        else if (SENSE_SETTINGS.matches(name) && data.has("value"))
                            c.getSharedPreferences("senses_settings", android.content.Context.MODE_PRIVATE).edit().putLong(name, data.optLong("value")).apply()
                        db.putInbox(id, "setting", data.toString(), System.currentTimeMillis())
                    }
                    Route.CALL -> {
                        db.putInbox(id, "call", data.toString(), System.currentTimeMillis())
                        // Athena's call_owner, behind his call_owner row (Ossa.fromAgent); an empty one is kept and ignored.
                        dev.apollo.ossa.CallAsk.of(id, data)?.let { ask ->
                            val (ok, detail) = dev.apollo.ossa.Ossa.fromAgent(c, ask)
                            db.setInboxResult(id, detail)
                            runCatching { server.actionResult(id, ok, detail) }
                        }
                    }
                    // What an agent is on now: the pixel characters (and their widget) follow it.
                    Route.ACTIVITY -> dev.apollo.pixel.PixelFeed.post(c, data)
                    Route.UNKNOWN -> db.putInbox(id, item.optString("kind"), data.toString(), System.currentTimeMillis())
                } } catch (e: Exception) {
                    // The network failing: the whole sync retries. Anything else is this item's own fault: logged and
                    // acked, so one bad item never holds up the rest.
                    if (Outbox.retryable(e)) throw e
                    dev.apollo.clio.Clio.warn("sync", "item skipped", "${item.optString("kind")} $id: ${e.javaClass.simpleName}")
                }
                acked += id
            }
            if (acked.isNotEmpty()) server.ack(acked)
            db.put(CURSOR, r.optLong("cursor", since).toString())
            val version = r.optJSONObject("apps")?.optLong("version", -1L) ?: -1L
            if (appsChanged || (version >= 0 && version.toString() != db.get(APPS_VERSION))) {
                pullApps(c, server)
                if (version >= 0) db.put(APPS_VERSION, version.toString())
                dev.apollo.ui.Themes.load(c)   // the chosen theme file may have changed
            }
            if (acked.isEmpty()) break
        }
    }

    /** One-way: the server's folder wins. Changed files are fetched; files the manifest no longer lists are removed. */
    fun pullApps(c: Context, server: Server = Server(c)) {
        val root = File(c.filesDir, APPS_DIR)
        val m = server.manifest()
        val keep = HashSet<String>()
        for (i in 0 until m.length()) {
            val e = m.getJSONObject(i)
            val app = e.getString("app"); val path = e.getString("path")
            if (!Outbox.safePath(app, path)) continue
            val f = File(root, "$app/$path")
            keep += f.canonicalPath
            if (f.isFile && f.length() == e.optLong("size", f.length()) && localSha(f) == e.optString("sha256")) continue
            val bytes = server.appFile(app, path)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        root.walkBottomUp().forEach { if (it.isFile && it.canonicalPath !in keep) it.delete() else if (it.isDirectory && it != root && it.list()?.isEmpty() == true) it.delete() }
        LocalDb.get(c).put("apps.pulled", System.currentTimeMillis().toString())
        dev.apollo.apps.Widgets.refresh(c)
        // The routines table may have changed: the alarm for its next time row.
        dev.apollo.actions.RoutineAlarm.arm(c)
    }

    /** path -> (modified, length, sha256): most files are unchanged from one pull to the next, so they aren't read again. */
    private val shas = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Long, String>>()

    private fun localSha(f: File): String {
        val hit = shas[f.path]
        if (hit != null && hit.first == f.lastModified() && hit.second == f.length()) return hit.third
        return sha256(f.readBytes()).also { shas[f.path] = Triple(f.lastModified(), f.length(), it) }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun now(c: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Sync.run(applicationContext)
            // Calypso drops ride along: ciphertext from Iris or Atlas, opened only with his fingerprint.
            runCatching { dev.apollo.calypso.CalypsoDrops.sync(applicationContext) }
            Result.success()
        } catch (e: ServerError) { if (e.code == 401) Result.success() else Result.retry() }
    }
}
