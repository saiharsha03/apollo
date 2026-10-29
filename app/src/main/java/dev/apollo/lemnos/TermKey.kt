package dev.apollo.lemnos

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.apollo.calypso.AndroidBiometric
import dev.apollo.net.ServerError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A server challenge signed by the terminal key: good for one terminal, new shell or close. */
class Proof(val nonce: String, val sig: String) {
    val query get() = mapOf("nonce" to nonce, "sig" to sig)
}

/**
 * What the terminal does next, from this phone's key id (null: none, or the phone invalidated it), the server's active key
 * id and its pending key's id (a registered key waiting for root's approval; someone else's needs a reset on the server).
 */
enum class KeyPlan { SIGN, REGISTER, APPROVE_ON_SERVER, RESET_ON_SERVER }

fun keyPlan(phone: String?, server: String?, pending: String?): KeyPlan = when {
    server != null -> if (phone == server) KeyPlan.SIGN else KeyPlan.RESET_ON_SERVER
    pending != null -> if (phone == pending) KeyPlan.APPROVE_ON_SERVER else KeyPlan.RESET_ON_SERVER
    else -> KeyPlan.REGISTER
}

/** The root command that lets this phone register a new key. */
const val TERM_KEY_RESET = "bash deploy/lemnos-reset.sh (as root, in the Apollo server folder)"

/** The root command that makes a registered key usable. */
fun termKeyApprove(code: String) = "bash deploy/lemnos-approve.sh $code"

/**
 * Lemnos's terminal key: one Keystore P-256 key, each signature opened by his fingerprint or PIN (auth timeout 0), so the
 * server sees his finger and not just the device token. A new fingerprint enrolment does not wipe it. Only the terminal
 * uses it: if it's ever gone (reinstall, screen lock removed), the terminal says so and nothing else notices.
 */
class TermKey(private val c: Context, private val alias: String = "lemnos") {
    /** The server holds another key: root has to clear it before this phone can register its own. */
    class NeedsReset : IOException("Terminal key was reset on this phone. Reset it on the server: $TERM_KEY_RESET. Then tap Make a new key.")

    /** Registered, waiting for root: a stolen device token alone can't enrol a key. */
    class NeedsApproval(code: String) : IOException("Approve this phone's terminal key on the server, as root: ${termKeyApprove(code)}. Then try again.")

    private fun ks() = KeyStore.getInstance(STORE).apply { load(null) }

    private fun der(): ByteArray? = runCatching { ks().getCertificate(alias)?.publicKey?.encoded }.getOrNull()

    private fun id(): String? = der()?.let { d -> MessageDigest.getInstance("SHA-256").digest(d).joinToString("") { "%02x".format(it) } }

    fun forget() { runCatching { ks().deleteEntry(alias) } }

    private fun make() {
        fun gen(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    .setInvalidatedByBiometricEnrollment(false)
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
        }.generateKeyPair()
        try { gen(true) } catch (_: Exception) {
            try { gen(false) } catch (e: Exception) { throw IOException("Couldn't make the terminal key (is a screen lock set?): ${e.message}") }
        }
    }

    /** A signer for the key, or null when the key is missing or the phone invalidated it (then it's deleted). */
    private fun signer(): Signature? {
        val k = runCatching { ks().getKey(alias, null) as? PrivateKey }.getOrNull() ?: return null
        return try {
            Signature.getInstance("SHA256withECDSA").apply { initSign(k) }
        } catch (_: KeyPermanentlyInvalidatedException) { forget(); null }
    }

    private suspend fun finger(title: String, s: Signature, msg: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { k ->
            AndroidBiometric(c).sign(title, "A terminal on your server", s, {
                runCatching { s.update(msg.toByteArray()); b64(s.sign()) }.onSuccess { k.resume(it) }.onFailure { k.resumeWithException(IOException("Signing failed: ${it.message}")) }
            }, { k.resumeWithException(IOException(it)) })
        }
    }

    /** A live challenge and a signer whose key the server holds. The first time, this phone's key is made and registered (then root approves it). */
    private suspend fun ready(l: Lemnos): Pair<String, Signature> {
        val (nonce, server, pending) = withContext(Dispatchers.IO) { l.challenge() }
        var s = signer()
        val mine = if (s != null) id() else null
        return when (keyPlan(mine, server, pending)) {
            KeyPlan.RESET_ON_SERVER -> throw NeedsReset()
            KeyPlan.APPROVE_ON_SERVER -> throw NeedsApproval(mine!!.take(16))
            KeyPlan.SIGN -> nonce to s!!
            KeyPlan.REGISTER -> {
                if (s == null) { withContext(Dispatchers.IO) { forget(); make() }; s = signer() ?: throw IOException("The terminal key isn't usable") }
                val sig = finger("Register this phone", s, "$nonce:register")
                val code = try {
                    withContext(Dispatchers.IO) { l.register(b64(der()!!), nonce, sig) }
                } catch (e: ServerError) { if (e.code == 409) throw NeedsReset() else throw e }
                throw NeedsApproval(code)
            }
        }
    }

    /** His fingerprint over a fresh challenge for [what]. */
    suspend fun prove(what: String, title: String): Proof {
        val (nonce, s) = ready(Lemnos(c))
        return Proof(nonce, finger(title, s, "$nonce:$what"))
    }

    /** After root cleared the server's key: drop this phone's and register a new one (it ends asking for root's approval). */
    suspend fun renew() { withContext(Dispatchers.IO) { forget() }; ready(Lemnos(c)) }

    private companion object {
        const val STORE = "AndroidKeyStore"
        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
