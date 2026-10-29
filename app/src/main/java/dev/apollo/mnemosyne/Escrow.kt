package dev.apollo.mnemosyne

import android.content.Context
import dev.apollo.calypso.Box
import dev.apollo.calypso.CalypsoCore
import dev.apollo.calypso.Envelope
import dev.apollo.calypso.KeystoreBoxKeys
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Calypso in backups, only while "Include Calypso" is on (Zeus's mnemosyne/Escrow.kt, the same idea).
 *
 * A Calypso item is sealed to Calypso's Keystore RSA key, which needs his fingerprint for every use and can't leave the
 * phone, so a backup can't carry that key. Instead each item's own AES key is escrowed: wrapped (RSA-OAEP) to an escrow
 * public key whose private half is kept only encrypted under a key derived from a Calypso code, shown to him once when
 * he turns it on and never kept on the phone (not the backup's recovery code: the phone keeps that one unlocked for the
 * nightly backup, so a key from it would let anything that reads the phone's storage open Calypso). The phone can add
 * items to the escrow with no prompt but can't open them without the code, so on the phone Calypso stays
 * fingerprint-only. An item's key is in hand at two moments: when it is sealed in the app and when he opens it with his
 * fingerprint; [Box.keyTap] escrows it then. Drops from Iris and Atlas join the escrow the first time he opens them (or
 * "Add older items", one fingerprint each). On a new phone the code opens the escrow key and every escrowed item is
 * wrapped again to that phone's Calypso key; its content (iv, ct) and label are unchanged, so the seal still checks.
 * An escrow from before the Calypso code (v1, under the recovery code) is dropped at start: he turns it on again.
 */
object Escrow {
    class Pair(val publicDer: ByteArray, val sealedPrivate: ByteArray)

    val AAD_V2 = "apollo/mnemosyne/v2 escrow".toByteArray()
    private val AAD = AAD_V2
    /** Old backups' escrow, under the recovery code. */
    val AAD_V1 = "apollo/mnemosyne/v1 escrow".toByteArray()

    /** The key the escrow's private half is sealed under, from the Calypso code's 32 bytes (the recovery code's for v1). */
    fun escrowKey(secret: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal("calypso escrow".toByteArray())

    fun newPair(escrowKey: ByteArray, random: SecureRandom = SecureRandom()): Pair {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(3072, random) }.generateKeyPair()
        return Pair(kp.public.encoded, seal(kp.private.encoded, escrowKey, AAD, random))
    }

    fun seal(plain: ByteArray, key: ByteArray, aad: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    /** Throws when the code is wrong or the blob was changed. [aad]: Apollo's, or Zeus's for its old backups. */
    fun openPrivate(sealed: ByteArray, escrowKey: ByteArray, aad: ByteArray = AAD): PrivateKey {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(escrowKey, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        c.updateAAD(aad)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(c.doFinal(sealed, 12, sealed.size - 12)))
    }

    fun publicKey(der: ByteArray): PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))

    fun wrap(pub: PublicKey, itemKey: ByteArray): ByteArray = Cipher.getInstance(Box.RSA).apply { init(Cipher.ENCRYPT_MODE, pub, Box.OAEP) }.doFinal(itemKey)

    fun unwrap(priv: PrivateKey, wrapped: ByteArray): ByteArray = Cipher.getInstance(Box.RSA).apply { init(Cipher.DECRYPT_MODE, priv, Box.OAEP) }.doFinal(wrapped)

    /** On the new phone: the item key from the escrow, wrapped again to this phone's Calypso key. */
    fun rewrap(priv: PrivateKey, escrowed: ByteArray, newCalypso: PublicKey): ByteArray {
        val k = unwrap(priv, escrowed)
        try { return wrap(newCalypso, k) } finally { k.fill(0) }
    }

    /** One item as a backup carries it: the envelope, never opened, plus who it is shown as. */
    fun itemJson(e: Envelope, shown: String?): JSONObject = JSONObject(e.toJson()).put("shown", shown ?: JSONObject.NULL)
}

/** The escrow's state on this phone (prefs calypso_escrow) and the backup / restore steps around it. */
object CalypsoEscrow {
    private fun sp(c: Context) = c.getSharedPreferences("calypso_escrow", Context.MODE_PRIVATE)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    fun on(c: Context) = sp(c).getBoolean("on", false)

    /** A fresh pair under a new Calypso code, not kept anywhere yet: it turns on only once he says he wrote the code down. */
    class Pending(val code: String, internal val pub: String, internal val priv: String)

    fun prepare(): Pending {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val pair = Escrow.newPair(Escrow.escrowKey(secret))
            return Pending(RecoveryCode.encode(secret), b64(pair.publicDer), b64(pair.sealedPrivate))
        } finally { secret.fill(0) }
    }

    /** Turns inclusion on with [pending] (his fingerprint first, on the screen), or off with null (escrow dropped). */
    fun set(c: Context, pending: Pending?) {
        val ed = sp(c).edit().clear().putBoolean("on", pending != null)
        if (pending != null) ed.putString("pub", pending.pub).putString("priv", pending.priv).putInt("v", 2)
        ed.commit()
        install(c)
    }

    /** At start and on every change: Calypso's key tap is set exactly while inclusion is on. */
    fun install(c: Context) {
        val app = c.applicationContext
        // A v1 escrow is locked under the recovery code, which this phone keeps unlocked: dropped, he turns it on again.
        if (on(app) && sp(app).getInt("v", 1) < 2) sp(app).edit().clear().commit()
        val pub = sp(app).getString("pub", null)?.let { runCatching { Escrow.publicKey(unb64(it)) }.getOrNull() }
        Box.keyTap = if (on(app) && pub != null) { id, key -> sp(app).edit().putString("item.$id", b64(Escrow.wrap(pub, key))).apply() } else null
    }

    fun escrowed(c: Context): Map<String, String> = sp(c).all.filterKeys { it.startsWith("item.") }.mapKeys { it.key.removePrefix("item.") }.mapValues { it.value as String }

    /** Items not yet in the escrow: open each once (one fingerprint each) and the tap adds it. */
    fun missing(c: Context): List<String> = escrowed(c).keys.let { have -> CalypsoCore.get(c).calypso.items().map { it.id }.filter { it !in have } }

    /** The backup's calypso/escrow.json and calypso/items.json, or null when inclusion is off. */
    fun export(c: Context): kotlin.Pair<String, String>? {
        if (!on(c)) return null
        val vault = CalypsoCore.get(c).db
        val shown = vault.sealedItems().associate { it.id to it.from }
        val esc = escrowed(c).filterKeys { it in shown }
        val items = JSONArray()
        esc.keys.forEach { id -> vault.envelope(id)?.let { items.put(Escrow.itemJson(it, shown[id])) } }
        val escrow = JSONObject().put("v", 2).put("public", sp(c).getString("pub", "")).put("private", sp(c).getString("priv", "")).put("items", JSONObject(esc as Map<*, *>))
        return escrow.toString() to items.toString()
    }

    /**
     * On the new phone, from a staged backup: every escrowed item wrapped again to this phone's Calypso key and put in
     * Calypso (ids already there are kept as they are), and the escrow carries on here. Returns how many came back.
     * A v2 escrow needs the Calypso code he typed ([calypsoCode]); a v1 one opens with the recovery code and isn't kept.
     */
    fun restore(c: Context, escrowJson: String, itemsJson: String, backupKey: ByteArray, calypsoCode: String?): Int {
        val o = JSONObject(escrowJson)
        val v2 = o.optInt("v", 1) >= 2
        val secret = if (v2) RecoveryCode.decode(calypsoCode ?: "") ?: throw IllegalArgumentException("This backup's Calypso needs its Calypso code (52 letters and digits)") else backupKey
        val priv = Escrow.openPrivate(unb64(o.getString("private")), Escrow.escrowKey(secret), if (v2) Escrow.AAD_V2 else Escrow.AAD_V1)
        val keys = o.getJSONObject("items")
        val calypso = CalypsoCore.get(c).calypso
        val pub = KeystoreBoxKeys().publicKey()
        var n = 0
        val a = JSONArray(itemsJson)
        for (i in 0 until a.length()) {
            val j = a.getJSONObject(i)
            val e = Envelope.parse(j.toString()) ?: continue
            val wrapped = keys.optString(e.id).takeIf { it.isNotEmpty() } ?: continue
            val wk = runCatching { Escrow.rewrap(priv, unb64(wrapped), pub) }.getOrNull() ?: continue
            if (calypso.restore(Envelope(e.id, e.kind, e.label, e.from, e.at, wk, e.iv, e.ct), j.optString("shown").ifEmpty { null })) n++
        }
        // A v2 escrow carries on here as it was (still locked under his Calypso code); a v1 one stays off until he turns it on.
        val ed = sp(c).edit().clear().putBoolean("on", v2)
        if (v2) {
            ed.putInt("v", 2).putString("pub", o.getString("public")).putString("priv", o.getString("private"))
            keys.keys().forEach { id -> ed.putString("item.$id", keys.getString(id)) }
        }
        ed.commit()
        install(c)
        return n
    }

    /** Zeus's old backups: the same escrow, under Zeus's phrase and AAD; items come from its vault.db export. */
    fun restoreZeus(c: Context, escrow: JSONObject, rows: List<Envelope>, shown: Map<String, String?>, escrowKey: ByteArray): Int {
        val priv = Escrow.openPrivate(unb64(escrow.getString("private")), escrowKey, "zeus/mnemosyne/v1 escrow".toByteArray())
        val keys = escrow.getJSONObject("items")
        val calypso = CalypsoCore.get(c).calypso
        val pub = KeystoreBoxKeys().publicKey()
        return rows.count { e ->
            val wrapped = keys.optString(e.id).takeIf { it.isNotEmpty() } ?: return@count false
            val wk = runCatching { Escrow.rewrap(priv, unb64(wrapped), pub) }.getOrNull() ?: return@count false
            calypso.restore(Envelope(e.id, e.kind, e.label, e.from, e.at, wk, e.iv, e.ct), shown[e.id])
        }
    }
}
