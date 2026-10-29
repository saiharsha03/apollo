package dev.apollo.calypso

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Sealing with Keystore keys that need no fingerprint. Carried over from Zeus byte for byte (brain/Seal.kt,
 * brain/Envelope.kt): Apollo installs over Zeus and must open what Zeus sealed, so the aliases, the value formats and
 * the data-key files stay exactly as they were.
 */
interface Sealer {
    fun seal(plain: String): String
    /** Null when the value can't be opened (key gone, corrupted). */
    fun open(sealed: String): String?
    fun sealBytes(plain: ByteArray): ByteArray
    fun openBytes(sealed: ByteArray): ByteArray?
}

/**
 * AES-256-GCM. With [envelope] (the default, once [SealKeys.dir] is set): a [DataKeys] data key in software, wrapped by
 * the alias's Keystore key. Values sealed with the Keystore key directly (IV in front) still open.
 * `envelope = false` keeps the Keystore-direct format for every seal (Calypso's vault).
 */
class KeystoreSealer(private val alias: String, private val envelope: Boolean = true) : Sealer {
    @Volatile private var key: SecretKey? = null

    private val direct = object : Sealer {
        override fun seal(plain: String) = throw UnsupportedOperationException()
        override fun open(sealed: String) = throw UnsupportedOperationException()
        override fun sealBytes(plain: ByteArray) = directSeal(plain)
        override fun openBytes(sealed: ByteArray) = directOpen(sealed)
    }
    private val keys: DataKeys? by lazy { if (envelope) SealKeys.keys(alias, direct) else null }

    private fun key(): SecretKey {
        key?.let { return it }
        synchronized(this) {
            key?.let { return it }
            val ks = KeyStore.getInstance(STORE).apply { load(null) }
            val k = (ks.getKey(alias, null) as? SecretKey) ?: run {
                fun make(strongBox: Boolean): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply {
                    init(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setIsStrongBoxBacked(strongBox)
                            .build(),
                    )
                }.generateKey()
                try { make(true) } catch (_: Exception) { make(false) }
            }
            key = k
            return k
        }
    }

    private fun directSeal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    private fun directOpen(sealed: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV))
        c.doFinal(sealed, IV, sealed.size - IV)
    } catch (_: Exception) { null }

    override fun sealBytes(plain: ByteArray): ByteArray = keys?.seal(plain) ?: directSeal(plain)

    // An old value's random IV can start like a data-key header (1 in 65,536): when that fails, the Keystore tries.
    override fun openBytes(sealed: ByteArray): ByteArray? = keys?.open(sealed) ?: directOpen(sealed)

    override fun seal(plain: String): String = Base64.encodeToString(sealBytes(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    override fun open(sealed: String): String? =
        runCatching { Base64.decode(sealed, Base64.NO_WRAP) }.getOrNull()?.let { openBytes(it) }?.toString(Charsets.UTF_8)

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}

/** Where the wrapped data keys live (no-backup storage, set at process start: `noBackupFilesDir/seal`), one file per alias. */
object SealKeys {
    @Volatile var dir: File? = null
    private val all = HashMap<String, DataKeys>()

    fun keys(alias: String, wrap: Sealer): DataKeys? = dir?.let { d -> synchronized(all) { all.getOrPut(alias) { DataKeys(wrap, File(d, "$alias.keys")) } } }
}

/**
 * Data keys: a random AES-256-GCM key does the work in software and only it is sealed by the Keystore ([wrap]).
 * Value format: [MAGIC][key version][12-byte IV][ciphertext + tag]. The wrapped keys live in [keys], one line per
 * version ("v:base64").
 */
class DataKeys(private val wrap: Sealer, private val keys: File, private val random: SecureRandom = SecureRandom()) {
    private val open = HashMap<Int, ByteArray>()
    private var wrapped: Map<Int, ByteArray>? = null

    fun seal(plain: ByteArray): ByteArray = synchronized(this) {
        val v = latest() ?: rotate()
        val iv = ByteArray(IV).also(random::nextBytes)
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(v), "AES"), GCMParameterSpec(TAG_BITS, iv)) }
        byteArrayOf(MAGIC, v.toByte()) + iv + c.doFinal(plain)
    }

    /** Null when it isn't a data-key value, or its key or tag doesn't check out. */
    fun open(sealed: ByteArray): ByteArray? {
        if (!looksLike(sealed)) return null
        val key = synchronized(this) { runCatching { key(sealed[1].toInt() and 0xff) }.getOrNull() } ?: return null
        return try {
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 2, IV)) }
            c.doFinal(sealed, 2 + IV, sealed.size - 2 - IV)
        } catch (_: Exception) { null }
    }

    private fun rotate(): Int {
        val next = (versions().keys.maxOrNull() ?: 0) + 1
        val k = ByteArray(32).also(random::nextBytes)
        val sealedKey = wrap.sealBytes(k)
        keys.parentFile?.mkdirs()
        val tmp = File(keys.parentFile, keys.name + ".tmp")
        tmp.writeText((versions() + (next to sealedKey)).entries.joinToString("\n") { (v, w) -> "$v:" + java.util.Base64.getEncoder().encodeToString(w) })
        if (!tmp.renameTo(keys)) { keys.delete(); tmp.renameTo(keys) }
        wrapped = versions() + (next to sealedKey)
        open[next] = k
        return next
    }

    private fun latest(): Int? = versions().keys.maxOrNull()

    private fun versions(): Map<Int, ByteArray> = wrapped ?: (if (!keys.exists()) emptyMap() else keys.readLines().mapNotNull { l ->
        val v = l.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
        v to java.util.Base64.getDecoder().decode(l.substringAfter(':'))
    }.toMap()).also { wrapped = it }

    private fun key(v: Int): ByteArray = open.getOrPut(v) {
        val w = versions()[v] ?: throw IllegalStateException("no data key $v")
        wrap.openBytes(w)?.takeIf { it.size == 32 } ?: throw IllegalStateException("data key $v can't be opened")
    }

    private fun looksLike(b: ByteArray) = b.size >= 2 + IV + TAG_BITS / 8 && b[0] == MAGIC && b[1] != 0.toByte()

    companion object {
        const val MAGIC: Byte = 0x5A
        private const val IV = 12
        private const val TAG_BITS = 128
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
