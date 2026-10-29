package dev.apollo.mnemosyne

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.apollo.calypso.SealedSecret
import dev.apollo.clio.Clio
import dev.apollo.net.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One backup on the server. */
data class BackupInfo(val id: String, val size: Long, val created: Long)

/**
 * Mnemosyne, simply: a nightly backup of Apollo's own databases, settings and the phone-only mini-app files (Calliope's
 * journal from Zeus), sealed on the phone ([Sealed]) with a 32-byte backup key kept in Calypso's keys (slot
 * `mnemosyne`) and shown to him once as a recovery code. The server stores ciphertext it can't open.
 *
 * Never in a backup: Calypso's vault and keys as they are (sealed to this phone's Keystore keys, so a copy opens nowhere
 * else, and its keys prefs hold the device token and API keys), Mnemosyne's own key, WorkManager / Firebase / WebView
 * state. Calypso's items go in only while "Include Calypso" is on, through the escrow (Escrow.kt): calypso/escrow.json
 * and calypso/items.json, still sealed, opened on a new phone with the recovery code.
 * Restore stages everything first and moves it in on the next start, before any store opens ([applyStaged]).
 */
object Mnemosyne {
    private const val SLOT = "mnemosyne"
    /** The key a staged restore is sealed under ([StageSeal]); made per restore, gone once it is applied. */
    private const val STAGE_SLOT = "mnemosyne_stage"
    private const val WORK = "mnemosyne.nightly"
    // facts.db is a send queue (its rows already reached the server or will from this phone): never backed up or restored.
    internal val SKIP_DB = Regex("^(vault.*|facts\\.db|androidx\\.work.*|.*google.*|.*firebase.*|.*datatransport.*)$", RegexOption.IGNORE_CASE)
    internal val SKIP_PREFS = Regex("^(calypso|facts|calypso_escrow|calypso_drops|mnemosyne|secure_clip|WebView.*|com\\.google.*|androidx.*|Firebase.*|.*firebase.*)\\.xml$", RegexOption.IGNORE_CASE)
    private const val PHONE_FILES = "zeus-apps"

    fun ready(c: Context) = SealedSecret(c, SLOT).has()

    /** Makes the backup key and returns its recovery code (shown once). Never replaces an existing key. */
    fun setUp(c: Context): String {
        check(!ready(c)) { "already set up" }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        SealedSecret(c, SLOT).save(RecoveryCode.encode(key))
        schedule(c)
        return RecoveryCode.encode(key)
    }

    /** The code again (the screen asks for his fingerprint first). */
    fun code(c: Context): String? = SealedSecret(c, SLOT).read()

    internal fun key(c: Context): ByteArray = code(c)?.let { RecoveryCode.decode(it) } ?: error("no backup key")

    fun schedule(c: Context) {
        if (!ready(c)) return
        val constraints = Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.UNMETERED).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build())
    }

    /** One backup now: sealed to a temp file in no-backup storage, uploaded, deleted. Returns its id. Blocking. */
    @Synchronized fun backUp(c: Context): String {
        val id = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()) +
            "-" + "%08x".format(SecureRandom().nextInt())
        val tmp = File(c.noBackupFilesDir, "mnemosyne-$id.abk")
        try {
            ZipOutputStream(Sealed.writer(tmp.outputStream().buffered(), key(c))).use { z ->
                fun put(name: String, f: File) { z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
                val counts = JSONObject()
                databases(c).forEach { f ->
                    // The WAL folded in first, so the file alone is the whole database.
                    runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { q -> q.moveToFirst() } } }
                    put("db/${f.name}", f)
                }
                counts.put("db", databases(c).size)
                val prefs = File(c.dataDir, "shared_prefs").listFiles().orEmpty().filter { it.isFile && !SKIP_PREFS.matches(it.name) }
                prefs.forEach { put("prefs/${it.name}", it) }
                counts.put("prefs", prefs.size)
                val phone = File(c.filesDir, PHONE_FILES)
                val files = phone.walkTopDown().filter { it.isFile && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") }.toList()
                files.forEach { put("files/$PHONE_FILES/" + it.relativeTo(phone).invariantSeparatorsPath, it) }
                counts.put("files", files.size)
                CalypsoEscrow.export(c)?.let { (escrow, items) ->
                    z.putNextEntry(ZipEntry("calypso/escrow.json")); z.write(escrow.toByteArray()); z.closeEntry()
                    z.putNextEntry(ZipEntry("calypso/items.json")); z.write(items.toByteArray()); z.closeEntry()
                    counts.put("calypso", org.json.JSONArray(items).length())
                }
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write(JSONObject().put("format", 1).put("id", id).put("created", System.currentTimeMillis()).put("counts", counts).toString().toByteArray())
                z.closeEntry()
            }
            Server(c).upload("/v1/backup/$id", tmp)
            c.getSharedPreferences("mnemosyne_state", Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).putString("last_id", id).apply()
            Clio.info("mnemosyne", "backup", "${tmp.length()} bytes")
            return id
        } finally { tmp.delete() }
    }

    fun last(c: Context): Long = c.getSharedPreferences("mnemosyne_state", Context.MODE_PRIVATE).getLong("last", 0)

    private fun databases(c: Context): List<File> = c.getDatabasePath("x").parentFile?.listFiles().orEmpty()
        .filter { it.isFile && !it.name.contains("-journal") && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") && !SKIP_DB.matches(it.name) }

    fun list(c: Context): List<BackupInfo> {
        val a = JSONObject(Server(c).getJson("/v1/backup")).optJSONArray("backups") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { BackupInfo(it.getString("id"), it.optLong("size"), it.optLong("created")) }
    }

    /**
     * Restore, step one: download [id], open it with the recovery code he typed (the whole file is read and checked),
     * and stage it. Nothing live changes; the next start moves it in. The code becomes this phone's backup key.
     * Returns a line about Calypso when its part didn't come back (no or wrong Calypso code): the rest restores anyway.
     */
    fun stage(c: Context, id: String, codeText: String, calypsoCode: String? = null): String? {
        var note: String? = null
        val key = RecoveryCode.decode(codeText) ?: throw IllegalArgumentException("That isn't a whole recovery code (52 letters and digits)")
        val tmp = File(c.noBackupFilesDir, "mnemosyne-restore.abk")
        val stage = File(c.noBackupFilesDir, "mnemosyne-stage")
        stage.deleteRecursively()
        try {
            Server(c).download("/v1/backup/$id", emptyMap(), tmp)
            ZipInputStream(Sealed.reader(tmp.inputStream().buffered(), key)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val out = File(stage, e.name).canonicalFile
                    require(out.path.startsWith(stage.canonicalPath + File.separator)) { "bad entry" }
                    if (e.isDirectory) continue
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                }
            }
            require(File(stage, "manifest.json").exists()) { "no manifest" }
            // Calypso's escrowed items go straight into this phone's Calypso, wrapped again to its key; nothing waits in the stage.
            // Only Calypso is skipped when its code is missing or wrong; everything else in the backup still comes back.
            File(stage, "calypso").takeIf { File(it, "escrow.json").isFile }?.let { d ->
                runCatching {
                    CalypsoEscrow.restore(c, File(d, "escrow.json").readText(), File(d, "items.json").takeIf { it.isFile }?.readText() ?: "[]", key, calypsoCode)
                }.onSuccess { Clio.info("mnemosyne", "calypso restored", "$it items") }.onFailure {
                    Clio.warn("mnemosyne", "calypso not restored", it.javaClass.simpleName)
                    note = "Calypso didn't come back (its Calypso code was missing or wrong); everything else will."
                }
            }
            File(stage, "calypso").deleteRecursively()
            val seal = ByteArray(32).also { SecureRandom().nextBytes(it) }
            SealedSecret(c, STAGE_SLOT).save(java.util.Base64.getEncoder().encodeToString(seal))
            File(stage, StageSeal.READY).writeText(StageSeal.of(stage, seal))
            if (!ready(c)) SealedSecret(c, SLOT).save(RecoveryCode.encode(key))
            Clio.info("mnemosyne", "staged")
            return note
        } catch (e: Exception) {
            stage.deleteRecursively(); throw e
        } finally { tmp.delete() }
    }

    fun staged(c: Context): Boolean = File(c.noBackupFilesDir, "mnemosyne-stage/ready").exists()

    /** At app start, before any store opens: a staged restore replaces each store whole. */
    fun applyStaged(c: Context) {
        val stage = File(c.noBackupFilesDir, "mnemosyne-stage")
        if (!File(stage, StageSeal.READY).exists()) return
        // Only a stage this phone sealed in stage() moves in: files planted under no_backup without the key never do.
        val seal = SealedSecret(c, STAGE_SLOT).read()?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        SealedSecret(c, STAGE_SLOT).delete()
        if (seal == null || !StageSeal.ok(stage, seal, File(stage, StageSeal.READY).readText())) {
            Clio.warn("mnemosyne", "restore", "stage not sealed by this phone; dropped")
            stage.deleteRecursively()
            return
        }
        runCatching {
            val dbDir = c.getDatabasePath("x").parentFile!!.also { it.mkdirs() }
            // A backup made before a store was left out may still hold it.
            File(stage, "db").listFiles().orEmpty().filter { !SKIP_DB.matches(it.name) }.forEach { f ->
                listOf("", "-wal", "-shm", "-journal").forEach { File(dbDir, f.name + it).delete() }
                f.copyTo(File(dbDir, f.name), overwrite = true)
            }
            val prefsDir = File(c.dataDir, "shared_prefs").also { it.mkdirs() }
            File(stage, "prefs").listFiles().orEmpty().filter { !SKIP_PREFS.matches(it.name) }.forEach { it.copyTo(File(prefsDir, it.name), overwrite = true) }
            File(stage, "files").takeIf { it.isDirectory }?.copyRecursively(c.filesDir, overwrite = true)
        }.onFailure { Clio.error("mnemosyne", "restore", it.javaClass.simpleName) }.onSuccess { Clio.info("mnemosyne", "restored") }
        stage.deleteRecursively()
    }
}

class BackupWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { Mnemosyne.backUp(applicationContext) }.fold({ Result.success() }, {
            Clio.warn("mnemosyne", "nightly", it.javaClass.simpleName)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        })
    }
}
