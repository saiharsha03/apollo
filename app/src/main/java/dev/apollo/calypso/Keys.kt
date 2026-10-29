package dev.apollo.calypso

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * His saved keys (Anthropic, CloudHost, GitHub, TomTom...), each sealed with one AES-256-GCM Keystore key that needs no
 * fingerprint, so the code that uses a key can read it in the background; seeing one is behind the fingerprint.
 * Carried over from Zeus unchanged (brain/KeyVault.kt): prefs "calypso", alias zeus.calypso.keys, slots "<slot>.ct",
 * "<slot>.iv", "<slot>.saved". The Anthropic key's slot is "anthropic". Call on IO.
 */
class SealedSecret(context: Context, private val slot: String) {
    private val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun has(): Boolean = sp.contains("$slot.ct")

    fun read(): String? {
        val ct = sp.getString("$slot.ct", null) ?: return null
        val iv = sp.getString("$slot.iv", null) ?: return null
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun save(value: String) {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        sp.edit().putString("$slot.ct", Base64.encodeToString(ct, Base64.NO_WRAP)).putString("$slot.iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putLong("$slot.saved", System.currentTimeMillis()).commit()
    }

    fun delete() { sp.edit().remove("$slot.ct").remove("$slot.iv").remove("$slot.saved").commit() }

    fun savedAt(): Long? = sp.getLong("$slot.saved", 0L).takeIf { it > 0 }

    companion object {
        private const val PREFS = "calypso"
        private const val STORE = "AndroidKeyStore"
        private const val ALIAS = "zeus.calypso.keys"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** Every slot Zeus saved a key in, so the keys page can list them. */
        fun slots(context: Context): List<String> =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.filter { it.endsWith(".ct") }.map { it.removeSuffix(".ct") }.sorted()

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance(STORE).apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            fun make(strongBox: Boolean): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setIsStrongBoxBacked(strongBox)
                        .build(),
                )
            }.generateKey()
            return try { make(true) } catch (_: Exception) { make(false) }
        }

        /** Never the key in full: the last four, the rest hidden. */
        fun mask(value: String): String = value.trim().let { if (it.length < 12) "•".repeat(8) else "•".repeat(8) + it.takeLast(4) }
    }
}
