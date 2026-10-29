package dev.apollo.mnemosyne

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup file, sealed on the phone with his backup key before anything leaves it (the server only ever holds this):
 * `ABK1 | salt (16) | check (8) | chunks`, each chunk `u32 length | AES-256-GCM ciphertext` under a per-file key
 * HMAC(key, "file" + salt), nonce = u32 last-flag | u64 index, AAD = the header. A changed, swapped, dropped or appended
 * chunk, a cut-off file or an edited header all fail; a wrong code reads as "wrong code" from [check]. Pure (javax only).
 */
object Sealed {
    private val MAGIC = "ABK1".toByteArray()
    const val CHUNK = 1 shl 20
    private const val HEADER = 4 + 16 + 8

    class WrongCode : IOException("That recovery code doesn't open this backup")
    class Damaged(why: String) : IOException("The backup is damaged: $why")

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach { update(it) } }.doFinal()

    private fun check(key: ByteArray, salt: ByteArray) = hmac(key, "check".toByteArray(), salt).copyOf(8)

    private fun nonce(index: Long, last: Boolean) = ByteBuffer.allocate(12).putInt(if (last) 1 else 0).putLong(index).array()

    private fun gcm(mode: Int, fileKey: ByteArray, index: Long, last: Boolean, aad: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, nonce(index, last)))
        updateAAD(aad)
    }

    /** An OutputStream that seals what is written to it into [out]; close() writes the last chunk. */
    fun writer(out: OutputStream, key: ByteArray, random: SecureRandom = SecureRandom()): OutputStream {
        require(key.size == 32)
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val header = MAGIC + salt + check(key, salt)
        val fileKey = hmac(key, "file".toByteArray(), salt)
        val data = DataOutputStream(out)
        data.write(header)
        return object : OutputStream() {
            private val buf = ByteArray(CHUNK)
            private var n = 0
            private var index = 0L
            private var closed = false

            private fun emit(last: Boolean) {
                val ct = gcm(Cipher.ENCRYPT_MODE, fileKey, index++, last, header).doFinal(buf, 0, n)
                data.writeInt(ct.size); data.write(ct)
                n = 0
            }

            override fun write(b: Int) { if (n == CHUNK) emit(false); buf[n++] = b.toByte() }

            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off; var left = len
                while (left > 0) {
                    if (n == CHUNK) emit(false)
                    val k = minOf(left, CHUNK - n)
                    System.arraycopy(b, o, buf, n, k); n += k; o += k; left -= k
                }
            }

            override fun close() { if (closed) return; closed = true; emit(true); data.flush(); out.close() }
        }
    }

    /** The plaintext, checked chunk by chunk as it is read. Throws [WrongCode] or [Damaged]. */
    fun reader(input: InputStream, key: ByteArray): InputStream {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER)
        try { data.readFully(header) } catch (_: EOFException) { throw Damaged("too short") }
        if (!header.copyOf(4).contentEquals(MAGIC)) throw Damaged("not an Apollo backup")
        val salt = header.copyOfRange(4, 20)
        if (!check(key, salt).contentEquals(header.copyOfRange(20, 28))) throw WrongCode()
        val fileKey = hmac(key, "file".toByteArray(), salt)
        return object : InputStream() {
            private var plain = ByteArray(0)
            private var pos = 0
            private var index = 0L
            private var ended = false

            private fun next(): Boolean {
                if (ended) { if (data.read() != -1) throw Damaged("data after the end"); return false }
                val len = try { data.readInt() } catch (_: EOFException) { throw Damaged("cut off") }
                if (len !in 16..(CHUNK + 16)) throw Damaged("bad chunk")
                val ct = ByteArray(len).also { try { data.readFully(it) } catch (_: EOFException) { throw Damaged("cut off") } }
                // The last chunk is the one that opens with the last flag; anything else must open as a middle one.
                plain = runCatching { gcm(Cipher.DECRYPT_MODE, fileKey, index, false, header).doFinal(ct) }.getOrNull()
                    ?: runCatching { gcm(Cipher.DECRYPT_MODE, fileKey, index, true, header).doFinal(ct) }.getOrNull()?.also { ended = true }
                    ?: throw Damaged("chunk ${index + 1} doesn't open")
                index++; pos = 0
                return true
            }

            override fun read(): Int {
                while (pos >= plain.size) if (!next()) return -1
                return plain[pos++].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= plain.size) if (!next()) return -1
                val k = minOf(len, plain.size - pos)
                System.arraycopy(plain, pos, b, off, k); pos += k
                return k
            }

            override fun close() = input.close()
        }
    }
}

/** His recovery code: the 32-byte backup key as 52 base32 letters in groups of four. Pure. */
object RecoveryCode {
    private const val ABC = "abcdefghijklmnopqrstuvwxyz234567"

    fun encode(key: ByteArray): String {
        val sb = StringBuilder()
        var acc = 0; var bits = 0
        for (b in key) {
            acc = (acc shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(ABC[(acc shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ABC[(acc shl (5 - bits)) and 31])
        return sb.chunked(4).joinToString(" ")
    }

    /** Spaces, dashes and case don't matter; null when it isn't a whole code. */
    fun decode(text: String): ByteArray? {
        val s = text.lowercase().filter { it.isLetterOrDigit() }
        if (s.length != 52 || s.any { it !in ABC }) return null
        val out = java.io.ByteArrayOutputStream()
        var acc = 0; var bits = 0
        for (ch in s) {
            acc = (acc shl 5) or ABC.indexOf(ch); bits += 5
            if (bits >= 8) { out.write((acc shr (bits - 8)) and 0xff); bits -= 8 }
        }
        return out.toByteArray().takeIf { it.size == 32 }
    }
}

/**
 * The seal on a staged restore (scan F6): an HMAC over every staged file's path and SHA-256, under a fresh key that only
 * this phone's Keystore opens. A stage planted by anything that can only write files (no key) doesn't apply. Pure.
 */
object StageSeal {
    const val READY = "ready"

    fun of(stage: java.io.File, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        stage.walkTopDown().filter { it.isFile && it.relativeTo(stage).path != READY }.map { it.relativeTo(stage).invariantSeparatorsPath to it }
            .sortedBy { it.first }.forEach { (rel, f) ->
                mac.update(rel.toByteArray(Charsets.UTF_8)); mac.update(0)
                mac.update(java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()))
            }
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    fun ok(stage: java.io.File, key: ByteArray, seal: String): Boolean =
        java.security.MessageDigest.isEqual(of(stage, key).toByteArray(), seal.trim().toByteArray())
}
