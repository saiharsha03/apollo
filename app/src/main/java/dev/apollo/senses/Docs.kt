package dev.apollo.senses

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.apollo.clio.Clio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** How a shared file is read. Pure. */
object DocRules {
    enum class How { TEXT, PDF, IMAGE, DOCX, NONE }

    fun how(mime: String?, name: String): How = when {
        mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> How.PDF
        mime?.contains("wordprocessingml") == true || name.endsWith(".docx", ignoreCase = true) -> How.DOCX
        mime?.startsWith("image/") == true -> How.IMAGE
        mime?.startsWith("text/") == true || name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "csv", "json", "html", "xml") -> How.TEXT
        else -> How.NONE
    }

    /** Only another app's content:// share: never file:// (Apollo's own private files) or one of Apollo's own providers. */
    /** [owner] is the package whose provider serves the authority (null when none does). "0@x" (Android's user-id form)
     *  is read as x, so it can't dress up one of Apollo's own providers. */
    fun allowed(scheme: String?, authority: String?, pkg: String, owner: String?): Boolean {
        val a = authority?.substringAfterLast('@') ?: return false
        return scheme == "content" && owner != null && owner != pkg && a != pkg && !a.startsWith("$pkg.")
    }

    const val MAX_BYTES = 25L * 1024 * 1024

    /** A page rendered for OCR: about 2x the page, never wider than [max] pixels. */
    fun renderWidth(pageWidth: Int, max: Int = 2200): Int = (pageWidth * 2).coerceIn(1, max)
}

/**
 * Document reading: he shares a photo, a scan, a PDF or a text file to Apollo, and its text goes to Athena as a
 * `document` fact (his own share, so it leaves the phone without another ask). Photos and scanned pages are read on the
 * phone by ML Kit's text recognition (its model lives in Play services); a PDF's own text is used when it has one.
 */
object Docs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Copies the shared file first (the share's permission ends with the activity), then reads it in the background. */
    fun take(c: Context, uri: Uri, mime: String?) {
        val owner = uri.authority?.substringAfterLast('@')?.let { c.packageManager.resolveContentProvider(it, 0)?.packageName }
        require(DocRules.allowed(uri.scheme, uri.authority, c.packageName, owner)) { "not a shared file" }
        val name = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "shared"
        val dir = File(c.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "${System.currentTimeMillis()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)}")
        c.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var n = 0L
                while (true) {
                    val r = input.read(buf); if (r < 0) break
                    n += r; if (n > DocRules.MAX_BYTES) { out.close(); f.delete(); throw IllegalStateException("over 25 MB") }
                    out.write(buf, 0, r)
                }
            }
        } ?: throw IllegalStateException("can't open it")
        val app = c.applicationContext
        scope.launch {
            val how = DocRules.how(mime, name)
            val r = runCatching { read(app, f, how) }
            f.delete()
            r.onSuccess { (text, pages) ->
                Senses.sink.post("document", JSONObject().put("name", name).put("mime", mime ?: "").put("how", how.name.lowercase()).put("pages", pages)
                    .put("text", text), true)
                toast(app, if (text.isBlank()) "No text found in $name; sent to Athena anyway" else "Sent $name to Athena")
            }.onFailure {
                Clio.log(app, "docs", "read failed", JSONObject().put("how", how.name).put("error", it.javaClass.simpleName))
                toast(app, "Couldn't read $name")
            }
        }
    }

    /** The text and how many pages it came from. */
    fun read(c: Context, f: File, how: DocRules.How): Pair<String, Int> = when (how) {
        DocRules.How.TEXT -> f.readText() to 1
        DocRules.How.IMAGE -> ocr(BitmapFactory.decodeFile(f.path) ?: throw IllegalStateException("not an image")) to 1
        DocRules.How.PDF -> pdf(f)
        DocRules.How.DOCX -> f.inputStream().use { DocText.docx(it) } to 1
        DocRules.How.NONE -> throw IllegalArgumentException("not a photo, PDF, Word or text file")
    }

    private fun pdf(f: File): Pair<String, Int> = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { pdf ->
            val pages = (0 until pdf.pageCount).map { i ->
                pdf.openPage(i).use { page ->
                    // Android 15 reads a PDF's own text; a scan (or older Android) is rendered and read by OCR.
                    val own = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) runCatching { page.textContents.joinToString("\n") { it.text } }.getOrDefault("") else ""
                    own.ifBlank {
                        val w = DocRules.renderWidth(page.width)
                        val bmp = Bitmap.createBitmap(w, (page.height.toLong() * w / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ocr(bmp).also { bmp.recycle() }
                    }
                }
            }
            pages.mapIndexed { i, t -> "[page ${i + 1}]\n$t" }.joinToString("\n\n") to pdf.pageCount
        }
    }

    private fun ocr(bmp: Bitmap): String {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS).text } finally { rec.close() }
    }

    private suspend fun toast(c: Context, text: String) = withContext(Dispatchers.Main) { Toast.makeText(c, text, Toast.LENGTH_SHORT).show() }
}

/** The share target ("Send to Athena"): takes the file or text and closes at once. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            when {
                uri != null -> Toast.makeText(this, runCatching { Docs.take(this, uri, intent.type ?: contentResolver.getType(uri)) }
                    .fold({ "Reading it for Athena" }, { "Can't read that: ${it.message ?: "not a file"}" }), Toast.LENGTH_SHORT).show()
                !text.isNullOrBlank() -> {
                    Senses.sink.post("document", JSONObject().put("name", intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "shared text").put("mime", "text/plain")
                        .put("how", "text").put("pages", 1).put("text", text), true)
                    Toast.makeText(this, "Sent to Athena", Toast.LENGTH_SHORT).show()
                }
                else -> Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { Toast.makeText(this, "Couldn't take that: ${it.javaClass.simpleName}", Toast.LENGTH_SHORT).show() }
        finish()
    }
}
