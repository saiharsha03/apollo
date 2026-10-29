package dev.apollo.net

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zeus kept Athena's mini apps (Calliope's journal, the fuel log...) in filesDir/apps. Apollo's apps live on the server,
 * so on first start that folder is renamed to zeus-apps (kept, never deleted) and, once the phone is paired, a zip of it
 * goes to the server once, where the apps are moved over as they were.
 */
object ZeusApps {
    private fun p(c: Context) = c.getSharedPreferences("zeus_apps", Context.MODE_PRIVATE)

    fun keep(c: Context) {
        val old = File(c.filesDir, "apps")
        val kept = File(c.filesDir, "zeus-apps")
        if (old.isDirectory && !kept.exists()) old.renameTo(kept)
    }

    /** Blocking; call off the main thread. True once the server has it (or there was nothing to send). */
    fun upload(c: Context): Boolean {
        if (p(c).getBoolean("sent", false)) return true
        val kept = File(c.filesDir, "zeus-apps")
        if (!kept.isDirectory) return true.also { p(c).edit().putBoolean("sent", true).apply() }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            kept.walkTopDown().filter { it.isFile && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(kept).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return runCatching { Server(c).importZeusApps(out.toByteArray()) }.isSuccess.also { if (it) p(c).edit().putBoolean("sent", true).apply() }
    }
}
