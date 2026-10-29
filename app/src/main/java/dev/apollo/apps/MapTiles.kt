package dev.apollo.apps

import android.content.Context
import android.util.LruCache
import dev.apollo.net.Server
import java.io.File

/**
 * Street map tiles for pages (/kit/apollomap.js, Zeus's zmap): a page asks for /tile/{z}/{x}/{y}.png, the server's
 * /v1/tiles route answers (TomTom's dark map, its key stays there). Kept in memory and in the phone's cache folder, so a
 * map he opens again costs nothing. Blocking: WebView asks off the main thread.
 */
object MapTiles {
    private const val MAX_Z = 20
    private const val DISK_BYTES = 60L * 1024 * 1024
    private val mem = object : LruCache<String, ByteArray>(8 * 1024 * 1024) { override fun sizeOf(k: String, v: ByteArray) = v.size }

    /** Pure: (z, x, y) from /tile/{z}/{x}/{y}.png or Zeus's /tile/{dark|light}/{z}/{x}/{y}; null when it isn't a real tile. */
    fun parse(path: String): Triple<Int, Int, Int>? {
        val m = Regex("""^/tile/(?:(?:dark|light)/)?(\d{1,2})/(\d{1,7})/(\d{1,7})(\.png)?$""").find(path) ?: return null
        val (z, x, y) = m.groupValues.drop(1).take(3).map { it.toInt() }
        if (z > MAX_Z || x >= (1 shl z) || y >= (1 shl z)) return null
        return Triple(z, x, y)
    }

    fun get(c: Context, z: Int, x: Int, y: Int): ByteArray? {
        val k = "$z/$x/$y"
        mem.get(k)?.let { return it }
        val dir = File(c.cacheDir, "tiles")
        val f = File(dir, "$z-$x-$y.png")
        val body = runCatching { f.readBytes().also { f.setLastModified(System.currentTimeMillis()) } }.getOrNull()
            ?: runCatching { Server(c).tile(z, x, y) }.getOrNull()?.also { b ->
                runCatching { dir.mkdirs(); f.writeBytes(b); trim(dir) }
            } ?: return null
        mem.put(k, body)
        return body
    }

    /** Oldest used out first, once the folder passes its cap. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        for (f in files) {
            if (total <= DISK_BYTES * 9 / 10) break
            total -= f.length(); f.delete()
        }
    }
}
