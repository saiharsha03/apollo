package dev.apollo.net

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One queued fact, as stored. [data] is the JSON object text; [cid] is its own random id (null on rows queued before it). */
data class QueuedFact(val id: Long, val kind: String, val at: Long, val data: String, val urgent: Boolean, val cid: String? = null)

/**
 * What Apollo senses goes to the server as facts (docs/API.md). [post] only queues: the queue (facts.db, no backup)
 * keeps every fact until the server took it, so nothing is lost offline. Urgent facts (a message from a person, a
 * missed call) are marked urgent for Athena; every fact uploads at once, the 15-minute run only catches stragglers.
 */
object Facts {
    /** The most facts in one POST: keeps a request small after a long offline stretch. A size limit, not a cap on facts. */
    const val BATCH = 200
    private const val WORK_NOW = "facts.now"
    private const val WORK_PERIODIC = "facts.periodic"

    fun post(c: Context, kind: String, data: JSONObject, urgent: Boolean = false, at: Long = System.currentTimeMillis()) {
        // Off-limits topics are withheld here, before the fact is even queued (senses/OffLimits).
        FactsDb.get(c).add(kind, at, dev.apollo.senses.OffLimits.scrub(kind, data).toString(), urgent)
        dev.apollo.ui.AthenaNow.factAt.value = System.currentTimeMillis()
        // Every fact goes at once (Owner, 2026-10-26); facts posted while an upload is waiting ride along with it.
        uploadNow(c)
    }

    fun uploadNow(c: Context) {
        val req = OneTimeWorkRequestBuilder<FactsWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<FactsWorker>(15, TimeUnit.MINUTES).setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** The next batch to send: urgent facts first, then oldest first, at most [max]. Pure. */
    fun nextBatch(queue: List<QueuedFact>, max: Int = BATCH): List<QueuedFact> =
        queue.sortedWith(compareByDescending<QueuedFact> { it.urgent }.thenBy { it.at }.thenBy { it.id }).take(max)

    /**
     * The wire form of a batch (docs/API.md `POST /v1/facts`). Pure. Each fact carries its own random id, so the server
     * adds a batch sent again (its answer lost) only once. A row id could repeat after a backup restore rolled facts.db
     * back, so it is only the fallback for rows queued before the random ids.
     */
    fun wire(batch: List<QueuedFact>, install: String): JSONArray = JSONArray().apply {
        batch.forEach { f -> put(JSONObject().put("id", wireId(f, install)).put("kind", f.kind).put("at", f.at).put("data", JSONObject(f.data)).put("urgent", f.urgent)) }
    }

    fun wireId(f: QueuedFact, install: String) = f.cid ?: "$install:${f.id}"

    /** A random id for this install, made once. */
    fun install(c: Context): String {
        val p = c.getSharedPreferences("facts", Context.MODE_PRIVATE)
        return p.getString("install", null) ?: java.util.UUID.randomUUID().toString().take(8).also { p.edit().putString("install", it).commit() }
    }
}

/** facts.db: the queue. Rows leave only once the server answered 2xx for them. */
class FactsDb private constructor(c: Context) : SQLiteOpenHelper(c, "facts.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE q (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, at INTEGER NOT NULL, data TEXT NOT NULL, urgent INTEGER NOT NULL, cid TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE q ADD COLUMN cid TEXT")
    }

    fun add(kind: String, at: Long, data: String, urgent: Boolean) {
        writableDatabase.insert("q", null, ContentValues().apply { put("kind", kind); put("at", at); put("data", data); put("urgent", if (urgent) 1 else 0); put("cid", java.util.UUID.randomUUID().toString()) })
    }

    /** Everything queued; the worker picks a batch with [Facts.nextBatch]. */
    fun all(): List<QueuedFact> = readableDatabase.rawQuery("SELECT id, kind, at, data, urgent, cid FROM q", null).use { c ->
        buildList { while (c.moveToNext()) add(QueuedFact(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4) != 0, c.getString(5))) }
    }

    fun remove(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL("DELETE FROM q WHERE id IN (${ids.joinToString(",")})")
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM q", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    companion object {
        @Volatile private var instance: FactsDb? = null
        fun get(c: Context): FactsDb = instance ?: synchronized(this) { instance ?: FactsDb(c.applicationContext).also { instance = it } }
    }
}

/** Uploads the queue in batches until it is empty. A failed call keeps every fact and retries with backoff. */
class FactsWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = FactsDb.get(applicationContext)
        val server = Server(applicationContext)
        try {
            var max = Facts.BATCH
            while (true) {
                val batch = Facts.nextBatch(db.all(), max)
                if (batch.isEmpty()) break
                try {
                    server.postFacts(Facts.wire(batch, Facts.install(applicationContext)))
                } catch (e: ServerError) {
                    if (Outbox.keepReport(e.code)) throw e
                    // Refused for good (one too-big fact, say): halve the batch until it is that one fact, drop only it.
                    if (batch.size > 1) { max = batch.size / 2; continue }
                    dev.apollo.clio.Clio.warn("facts", "dropped", "${batch[0].kind}: HTTP ${e.code}")
                    max = Facts.BATCH
                }
                db.remove(batch.map { it.id })
            }
            Result.success()
        } catch (e: ServerError) {
            // No token yet (401 before pairing) waits for the next periodic run instead of spinning.
            if (e.code == 401) Result.success() else Result.retry()
        }
    }
}
