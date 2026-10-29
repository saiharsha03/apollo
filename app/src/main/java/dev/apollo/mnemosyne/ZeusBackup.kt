package dev.apollo.mnemosyne

import android.content.Context
import dev.apollo.calypso.AegisScope
import dev.apollo.calypso.CalypsoCore
import dev.apollo.calypso.Envelope
import dev.apollo.calypso.ItemKind
import dev.apollo.calypso.KeystoreBoxKeys
import dev.apollo.calypso.Unwrapper
import dev.apollo.clio.Clio
import dev.apollo.net.Server
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zeus's recovery phrase: 24 BIP39 words for 256 bits, the last word carrying an 8-bit checksum (Zeus's mnemosyne/Crypto.kt
 * Phrase, unchanged). The wordlist is bundled (resources/mnemosyne/bip39-english.txt). Pure.
 */
object Phrase {
    val words: List<String> by lazy {
        Phrase::class.java.getResourceAsStream("/mnemosyne/bip39-english.txt")!!.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            .also { check(it.size == 2048) { "wordlist" } }
    }
    private val index: Map<String, Int> by lazy { words.withIndex().associate { (i, w) -> w to i } }
    private val prefix: Map<String, Int> by lazy { words.withIndex().associate { (i, w) -> w.take(4) to i } }

    class Invalid(message: String) : Exception(message)

    fun encode(entropy: ByteArray): List<String> {
        require(entropy.size == 32)
        val bits = BooleanArray(264)
        for (i in 0 until 256) bits[i] = (entropy[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
        val check = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt()
        for (i in 0 until 8) bits[256 + i] = (check shr (7 - i)) and 1 == 1
        return (0 until 24).map { w -> words[(0 until 11).fold(0) { acc, b -> (acc shl 1) or if (bits[w * 11 + b]) 1 else 0 }] }
    }

    fun decode(text: String): ByteArray {
        val given = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (given.size != 24) throw Invalid("Zeus's phrase has 24 words; this has ${given.size}.")
        val idx = given.mapIndexed { i, w -> index[w] ?: (if (w.length >= 4) prefix[w.take(4)] else null) ?: throw Invalid("Word ${i + 1} (\"$w\") isn't on the list.") }
        val bits = BooleanArray(264)
        idx.forEachIndexed { w, v -> for (b in 0 until 11) bits[w * 11 + b] = (v shr (10 - b)) and 1 == 1 }
        val entropy = ByteArray(32)
        for (i in 0 until 256) if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        val check = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xff
        val got = (0 until 8).fold(0) { acc, b -> (acc shl 1) or if (bits[256 + b]) 1 else 0 }
        if (check != got) throw Invalid("Those words don't check out. One is wrong or two are swapped.")
        return entropy
    }
}

/** Zeus's keys from the phrase (HKDF-SHA256, salt "zeus/mnemosyne/v1"). Pure. */
object ZeusKeys {
    private val SALT = "zeus/mnemosyne/v1".toByteArray()

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var n = 1
        while (out.size() < length) {
            mac.update(t); mac.update(info.toByteArray()); mac.update(n.toByte())
            t = mac.doFinal(); out.write(t); n++
        }
        return out.toByteArray().copyOf(length)
    }

    fun backupKey(entropy: ByteArray) = hkdf(entropy, SALT, "backup key")
    fun escrowKey(entropy: ByteArray) = hkdf(entropy, SALT, "calypso escrow")
    fun check(backupKey: ByteArray): String = hkdf(backupKey, SALT, "key check", 8).joinToString("") { "%02x".format(it) }
}

/**
 * Zeus's backup file (.zbk v1), read only: "ZBK1" | u32 header length | header JSON | chunks, each u32 length |
 * AES-256-GCM(HKDF(backupKey, salt, "file key"), nonce = u64 index | u32 last, aad = sha256(header)). Zeus's Zbk reader.
 */
object Zbk {
    private val MAGIC = "ZBK1".toByteArray()
    private const val CHUNK = 1 shl 20
    class WrongKey : IOException("That phrase doesn't open this backup.")
    class Damaged(why: String) : IOException("The backup is damaged: $why")

    /** One string or number field of the flat header JSON Zeus wrote. */
    private fun field(json: String, name: String): String? =
        Regex("\"" + Regex.escape(name) + """"\s*:\s*(?:"([^"]*)"|(-?\d+))""").find(json)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }

    private fun nonce(i: Long, last: Boolean) = ByteBuffer.allocate(12).putLong(i).putInt(if (last) 1 else 0).array()

    fun reader(input: InputStream, backupKey: ByteArray): InputStream {
        val din = DataInputStream(input)
        val magic = ByteArray(4)
        try { din.readFully(magic) } catch (_: EOFException) { throw Damaged("not a Zeus backup") }
        if (!magic.contentEquals(MAGIC)) throw Damaged("not a Zeus backup")
        val len = din.readInt()
        if (len !in 2..4096) throw Damaged("header")
        val raw = ByteArray(len).also { din.readFully(it) }
        val h = String(raw, Charsets.UTF_8)
        if (field(h, "v") != "1" || field(h, "chunk") != CHUNK.toString()) throw Damaged("made by a newer Zeus")
        if (field(h, "check") != ZeusKeys.check(backupKey)) throw WrongKey()
        val salt = field(h, "salt")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: throw Damaged("header")
        val key = SecretKeySpec(ZeusKeys.hkdf(backupKey, salt, "file key"), "AES")
        val aad = MessageDigest.getInstance("SHA-256").digest(raw)
        return object : InputStream() {
            private var cur = ByteArray(0)
            private var pos = 0
            private var index = 0L
            private var done = false

            private fun next(): Boolean {
                if (done) return false
                val n = try { din.readInt() } catch (_: EOFException) { throw Damaged("cut short") }
                if (n !in 16..CHUNK + 16) throw Damaged("chunk")
                val ct = ByteArray(n).also { try { din.readFully(it) } catch (_: EOFException) { throw Damaged("cut short") } }
                fun open(last: Boolean): ByteArray? = try {
                    Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(index, last))); updateAAD(aad); doFinal(ct) }
                } catch (_: AEADBadTagException) { null }
                val mid = if (n == CHUNK + 16) open(false) else null
                cur = mid ?: open(true)?.also { done = true } ?: throw Damaged("chunk ${index + 1} doesn't open")
                index++; pos = 0
                if (done && din.read() != -1) throw Damaged("extra data after its end")
                return true
            }

            override fun read(): Int {
                while (pos >= cur.size) if (!next()) return -1
                return cur[pos++].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= cur.size) if (!next()) return -1
                val k = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, k); pos += k
                return k
            }

            override fun close() = input.close()
        }
    }
}

/**
 * Restoring from Zeus's old backups (W8): what still means something to Apollo comes back, the rest stays in the file.
 * - Calypso: its items, through Zeus's escrow when "Include Calypso" was on there, wrapped again to this phone's key.
 * - Aegis: its shielded items (they were exported opened, inside the sealed backup).
 * - Calliope and the other mini apps' phone files (zeus-apps/), and the closet (closet/), where nothing is there yet.
 * Zeus's other stores (brain, senses, drives...) have no Apollo home and are left out. Only ids or files not already on
 * this phone are added; nothing here is replaced. Blocking.
 */
object ZeusBackup {
    data class Report(val calypso: Int, val calypsoLost: Int, val aegis: Int, val files: Int)

    fun list(c: Context): List<BackupInfo> {
        val a = JSONObject(Server(c).getJson("/v1/backup/zeus")).optJSONArray("backups") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { BackupInfo(it.getString("id"), it.optLong("size"), it.optLong("created")) }
    }

    fun restore(c: Context, id: String, words: String): Report {
        val entropy = Phrase.decode(words)
        val key = ZeusKeys.backupKey(entropy)
        val tmp = File(c.noBackupFilesDir, "zeus-restore.zbk")
        var escrow: JSONObject? = null
        val rows = ArrayList<Pair<Envelope, String?>>()
        var aegis = 0
        var files = 0
        try {
            Server(c).download("/v1/backup/zeus/$id", emptyMap(), tmp)
            ZipInputStream(Zbk.reader(tmp.inputStream().buffered(1 shl 16), key)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val n = e.name
                    when {
                        e.isDirectory -> {}
                        n == "calypso/escrow.json" -> escrow = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                        n == "db/vault.db.json" -> {
                            val dump = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                            rows += calypsoRows(dump)
                            aegis = aegisRows(c, dump)
                        }
                        n.startsWith("files/files/apps/") -> files += place(z, File(c.filesDir, "zeus-apps"), n.removePrefix("files/files/apps/"))
                        n.startsWith("files/files/closet/") -> files += place(z, File(c.filesDir, "closet"), n.removePrefix("files/files/closet/"))
                    }
                }
            }
            val cal = escrow?.let { CalypsoEscrow.restoreZeus(c, it, rows.map { r -> r.first }, rows.associate { r -> r.first.id to r.second }, ZeusKeys.escrowKey(entropy)) } ?: 0
            Clio.info("mnemosyne", "zeus backup restored", "calypso $cal of ${rows.size}, aegis $aegis, files $files")
            return Report(cal, rows.size - cal, aegis, files)
        } finally {
            key.fill(0); entropy.fill(0)
            tmp.delete()
        }
    }

    /** A file from the backup into [root], unless something is already there. 1 when written. */
    private fun place(z: ZipInputStream, root: File, rel: String): Int {
        val base = root.canonicalFile
        val out = File(base, rel).canonicalFile
        if (!out.path.startsWith(base.path + File.separator) || out.exists() || out.name.endsWith("-wal") || out.name.endsWith("-shm")) return 0
        out.parentFile?.mkdirs()
        out.outputStream().use { z.copyTo(it) }
        return 1
    }

    private fun table(dump: JSONObject, name: String): Pair<List<String>, JSONArray>? {
        val ts = dump.optJSONArray("tables") ?: return null
        for (i in 0 until ts.length()) {
            val t = ts.getJSONObject(i)
            if (t.optString("name") == name) return List(t.getJSONArray("cols").length()) { t.getJSONArray("cols").getString(it) } to t.getJSONArray("rows")
        }
        return null
    }

    /** A cell of Zeus's export: plain values, {"st": opened text}, {"sb"|"b": base64 bytes}, {"f": double}. */
    private fun text(v: Any?): String? = when (v) {
        is String -> v
        is JSONObject -> v.optString("st").takeIf { v.has("st") }
        is Number -> v.toString()
        else -> null
    }

    private fun bytes(v: Any?): ByteArray? = (v as? JSONObject)?.let { o -> (o.optString("sb").ifEmpty { o.optString("b") }).takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) } }

    private fun calypsoRows(dump: JSONObject): List<Pair<Envelope, String?>> {
        val (cols, rows) = table(dump, "calypso") ?: return emptyList()
        fun at(r: JSONArray, col: String) = cols.indexOf(col).takeIf { it >= 0 }?.let { r.opt(it) }
        return (0 until rows.length()).mapNotNull { i ->
            val r = rows.getJSONArray(i)
            runCatching {
                Envelope(text(at(r, "id"))!!, ItemKind.of(text(at(r, "kind")))!!, text(at(r, "label"))!!, text(at(r, "sender"))!!, (at(r, "at") as Number).toLong(),
                    bytes(at(r, "wk"))!!, bytes(at(r, "iv"))!!, bytes(at(r, "ct"))!!) to text(at(r, "shown"))
            }.getOrNull()
        }
    }

    private fun aegisRows(c: Context, dump: JSONObject): Int {
        val (cols, rows) = table(dump, "aegis") ?: return 0
        val a = CalypsoCore.get(c).aegis
        val have = a.items().map { it.name }.toSet()
        var n = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONArray(i)
            fun at(col: String) = cols.indexOf(col).takeIf { it >= 0 }?.let { r.opt(it) }
            val name = text(at("name")) ?: continue
            val value = (at("value") as? JSONObject)?.optString("st")?.takeIf { it.isNotEmpty() } ?: continue
            if (name in have) continue
            runCatching { a.put(name, value, text(at("note")).orEmpty(), AegisScope.of(text(at("scope"))), via = "from a Zeus backup") }.onSuccess { n++ }
        }
        return n
    }

    // ---- Zeus's phrase, still on this phone: wrapped to Zeus's Keystore key, opened only with his fingerprint ----

    private const val PHRASE_ALIAS = "zeus.mnemosyne.phrase"

    /** True when Zeus left its phrase here (prefs mnemosyne, key phrase) and its Keystore key still exists. */
    fun hasPhrase(c: Context): Boolean = c.getSharedPreferences("mnemosyne", Context.MODE_PRIVATE).contains("phrase") &&
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(PHRASE_ALIAS) }.getOrDefault(false)

    /** The cipher BiometricPrompt must authorise to show it. */
    fun phraseUnwrapper(): Unwrapper = KeystoreBoxKeys(PHRASE_ALIAS).unwrapper()

    fun phraseWords(c: Context, u: Unwrapper): List<String> {
        val ct = c.getSharedPreferences("mnemosyne", Context.MODE_PRIVATE).getString("phrase", null) ?: error("no Zeus phrase")
        val e = u.unwrap(Base64.getDecoder().decode(ct))
        try { return Phrase.encode(e) } finally { e.fill(0) }
    }
}
