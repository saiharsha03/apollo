package dev.apollo.senses

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.apollo.clio.Clio
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** A Word file's text without a library: a .docx is a zip, its words are in word/document.xml. Pure. */
object DocText {
    fun docx(input: InputStream): String = ZipInputStream(input).use { z ->
        var e = z.nextEntry
        while (e != null && e.name != "word/document.xml") e = z.nextEntry
        if (e == null) return ""
        xml(z.readBytes().toString(Charsets.UTF_8))
    }

    /** Paragraphs and line breaks to new lines, tabs kept, every tag dropped, the five XML entities read back. */
    fun xml(s: String): String = s.replace("</w:p>", "\n").replace(Regex("<w:(br|cr)\\s*/>"), "\n").replace(Regex("<w:tab\\s*/>"), "\t")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("\\n{3,}"), "\n\n").trim()

    /** Has this file changed since it was sent: its modified time and size, as a signature. */
    fun signature(modified: Long, size: Long): String = "$modified:$size"
}

/**
 * Documents from folders he picks (Android's folder picker; a persisted read grant per folder, no permission). Every
 * PDF, Word and text file under them goes to Athena as a `document` fact (how folder), and again when it changes.
 * Photos in those folders are not read. Checked every 6 hours and right after he adds a folder.
 */
object DocFolders {
    private fun prefs(c: Context) = c.getSharedPreferences("senses_docfolders", Context.MODE_PRIVATE)
    private fun sent(c: Context) = c.getSharedPreferences("senses_docfolders_sent", Context.MODE_PRIVATE)

    fun list(c: Context): Set<String> = prefs(c).getStringSet("trees", emptySet()).orEmpty()

    fun add(c: Context, tree: Uri) {
        c.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(c).edit().putStringSet("trees", list(c) + tree.toString()).apply()
        now(c)
    }

    fun remove(c: Context, tree: String) {
        runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs(c).edit().putStringSet("trees", list(c) - tree).apply()
    }

    /** The folder's name as the picker showed it ("primary:Documents/Bills" -> "Documents/Bills"). */
    fun label(tree: String): String = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(tree)).substringAfter(':') }.getOrDefault(tree).ifBlank { "storage" }

    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("docs.folders", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<DocFoldersWorker>(6, TimeUnit.HOURS).build())
    }

    fun now(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork("docs.folders.now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DocFoldersWorker>().build())
    }

    private data class Found(val uri: Uri, val name: String, val mime: String, val modified: Long, val size: Long, val folder: String)

    private fun walk(c: Context, tree: Uri): List<Found> {
        val out = ArrayList<Found>()
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
        val todo = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to label(tree.toString())))
        while (todo.isNotEmpty()) {
            val (doc, folder) = todo.removeFirst()
            c.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc), cols, null, null, null)?.use { k ->
                while (k.moveToNext()) {
                    val id = k.getString(0); val name = k.getString(1) ?: continue; val mime = k.getString(2).orEmpty()
                    if (name.startsWith(".")) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) todo.addLast(id to "$folder/$name")
                    else out += Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime, k.getLong(3), k.getLong(4), folder)
                }
            }
        }
        return out
    }

    /** One pass over every folder. A folder Android no longer lets Apollo read is logged and left for him to re-add. */
    fun scan(c: Context) {
        val s = sent(c)
        for (tree in list(c)) {
            val files = runCatching { walk(c, Uri.parse(tree)) }.onFailure { Clio.log(c, "docs", "folder unreadable", JSONObject().put("error", it.javaClass.simpleName)) }.getOrNull() ?: continue
            for (f in files) {
                val how = DocRules.how(f.mime, f.name)
                if (how == DocRules.How.NONE || how == DocRules.How.IMAGE || f.size > DocRules.MAX_BYTES) continue
                val sig = DocText.signature(f.modified, f.size)
                if (s.getString(f.uri.toString(), null) == sig) continue
                val tmp = File(c.cacheDir, "folder-doc").apply { delete() }
                runCatching {
                    c.contentResolver.openInputStream(f.uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    val (text, pages) = Docs.read(c, tmp, how)
                    Senses.sink.post("document", JSONObject().put("name", f.name).put("mime", f.mime).put("how", how.name.lowercase()).put("pages", pages)
                        .put("text", text).put("via", "folder").put("folder", f.folder).put("modified", f.modified), false)
                    s.edit().putString(f.uri.toString(), sig).apply()
                }.onFailure { Clio.log(c, "docs", "folder file unread", JSONObject().put("how", how.name).put("error", it.javaClass.simpleName)) }
                tmp.delete()
            }
        }
    }
}

class DocFoldersWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        runCatching { DocFolders.scan(applicationContext) }
        return Result.success()
    }
}
