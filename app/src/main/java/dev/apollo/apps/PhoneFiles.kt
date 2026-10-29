package dev.apollo.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import dev.apollo.clio.Clio
import dev.apollo.net.Facts
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone-only half of a mini app: filesDir/zeus-apps/<app>/ (Zeus's app folder, kept when Apollo moved in; Mnemosyne
 * backs it up). His voice notes (audio/) and photos (photos/) land here and never go to the server: the server's folder
 * is the mirror's source and the phone never writes it. Pages see these files at the same relative paths as the mirrored
 * ones (AppWeb serves media from here when the mirror lacks them), so Zeus's old recordings play where they were.
 */
object PhoneFiles {
    const val DIR = "zeus-apps"
    private val APP = Regex("^[a-z][a-z0-9-]{1,39}$")
    /** Only media is served from here: a page's code always comes from the mirror. */
    private val MEDIA = setOf("m4a", "aac", "ogg", "opus", "wav", "mp3", "jpg", "jpeg", "png", "webp")

    fun root(c: Context) = File(c.filesDir, DIR)

    /** The file for [path] in [app]'s phone folder (it may not exist yet), or null if it would leave it. */
    fun file(c: Context, app: String, path: String): File? {
        if (!APP.matches(app) || path.split('/').any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
        val dir = File(root(c), app).canonicalFile
        val f = File(dir, path).canonicalFile
        return f.takeIf { it.path.startsWith(dir.path + File.separator) }
    }

    /** A media file a page may load from here. */
    fun media(c: Context, app: String, path: String): File? =
        file(c, app, path)?.takeIf { it.isFile && it.extension.lowercase() in MEDIA }
}

/**
 * A page asks for a photo (closet: a new piece of clothing). The page's composable (AppPage) runs the camera or the photo
 * picker; the photo is scaled down and kept as photos/<stamp>.jpg in the app's phone folder, and the page gets the path.
 */
object PhotoAsk {
    data class Ask(val app: String, val camera: Boolean, val answer: (String) -> Unit)
    val current = MutableStateFlow<Ask?>(null)

    private const val MAX_SIDE = 1600

    /** From the picker or the camera: kept, or an error, back to the page. Blocking: off the main thread. */
    fun done(c: Context, ask: Ask, uri: Uri?) {
        val out = if (uri == null) JSONObject().put("error", "no photo") else runCatching {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
                val s = maxOf(info.size.width, info.size.height)
                if (s > MAX_SIDE) d.setTargetSize(info.size.width * MAX_SIDE / s, info.size.height * MAX_SIDE / s)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val name = "photos/" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".jpg"
            val f = PhoneFiles.file(c, ask.app, name) ?: error("no app")
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            Clio.info("apps", "photo kept", ask.app)
            JSONObject().put("path", name)
        }.getOrElse { JSONObject().put("error", "couldn't keep the photo (${it.javaClass.simpleName})") }
        ask.answer(out.toString())
    }
}

/**
 * Zeus's closet (Venus: filesDir/closet, closet-index.csv and its photos, catalogued by Iris) into the closet mini app,
 * once: the photos are copied into the app's phone folder, and when the app's items table is still empty each piece
 * becomes a row (a page_write fact, the way the page writes). Zeus's folder is left as it was.
 */
object ClosetImport {
    private const val APP = "closet"

    fun runOnce(c: Context) {
        val p = c.getSharedPreferences("closet_import", Context.MODE_PRIVATE)
        if (p.getBoolean("done", false)) return
        val src = File(c.filesDir, "closet")
        val csv = File(src, "closet-index.csv")
        if (!csv.isFile) { p.edit().putBoolean("done", true).apply(); return }
        val mirror = File(MiniApps.root(c), "$APP/data.db")
        if (!mirror.isFile) return   // the app hasn't synced yet: try again next time
        val rows = Csv.parse(csv.readText())
        if (rows.size < 2) { p.edit().putBoolean("done", true).apply(); return }
        val bases = listOf(File(src, "static"), File(src, "closet/static"))
        fun photo(rel: String): String? {
            val f = bases.map { File(it, rel) }.firstOrNull { it.isFile } ?: return null
            val name = "photos/zeus-" + rel.replace('/', '-')
            val out = PhoneFiles.file(c, APP, name) ?: return null
            if (!out.isFile) { out.parentFile?.mkdirs(); f.copyTo(out) }
            return name
        }
        val empty = (ZeusSql.one(mirror, "SELECT count(*) FROM items")?.toIntOrNull() ?: 0) == 0
        val head = rows.first().map { it.trim().lowercase() }
        fun col(n: String) = head.indexOfFirst { it.startsWith(n) }
        val iId = col("id"); val iCat = col("category"); val iKind = col("kind"); val iColour = col("colour")
        val iDesc = col("description"); val iNotes = col("notes"); val iPhotos = col("photos"); val iArch = col("archived")
        var n = 0
        for (r in rows.drop(1)) {
            fun at(i: Int) = r.getOrNull(i)?.trim().orEmpty()
            if (at(iId).isEmpty() || at(iArch) == "1") continue
            // His own photo first (primary), else the studio render Iris made.
            val own = at(iPhotos).split('|').map { it.trim().substringAfter(':') }.firstOrNull { it.isNotEmpty() && !it.startsWith("products/") }
            val pic = own?.let(::photo) ?: photo("products/${at(iId)}.jpg")
            if (!empty) continue
            val kind = Csv.kindOf(at(iCat), at(iKind))
            Facts.post(c, "page_write", JSONObject().put("app", APP)
                .put("sql", "INSERT INTO items (photo, kind, name, color, notes, added) VALUES (?, ?, ?, ?, ?, ?)")
                .put("params", JSONArray(listOf(pic, kind, "${at(iColour)} ${at(iKind)}".trim(), at(iColour), listOf(at(iDesc), at(iNotes)).filter { it.isNotEmpty() }.joinToString(". "), System.currentTimeMillis()))))
            n++
        }
        Clio.info("apps", "closet from Zeus", "$n pieces")
        p.edit().putBoolean("done", true).apply()
    }
}

/** A small CSV reader (quotes, doubled quotes, newlines in quotes) and Zeus's closet groups as the app's kinds. Pure. */
object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> { row.add(cell.toString()); cell.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = ArrayList()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); if (row.any { it.isNotEmpty() }) rows.add(row) }
        return rows
    }

    fun kindOf(category: String, kind: String): String {
        val s = "$category $kind".lowercase()
        return when {
            listOf("shoe", "sneaker", "boot", "sandal", "loafer").any { it in s } -> "shoes"
            listOf("jacket", "coat", "outer", "parka", "fleece", "hoodie").any { it in s } -> "outer"
            listOf("pant", "jean", "short", "trouser", "chino", "bottom", "jogger").any { it in s } -> "bottom"
            listOf("accessor", "belt", "hat", "cap", "watch", "bag", "care", "sock", "scarf", "glove").any { it in s } -> "accessory"
            else -> "top"
        }
    }
}
