package dev.apollo.calypso

import android.content.Context
import dev.apollo.net.Server
import org.json.JSONArray
import org.json.JSONObject

/**
 * Calypso drops from Iris and Atlas (server/apollo/calypso.py), as Zeus took them (vault/VaultCore.syncDrops): publish
 * Calypso's public key once so they can seal to it, take in what they dropped, ack it so the server forgets it. The
 * server only ever holds ciphertext. Blocking: call off the main thread. Returns how many arrived.
 */
object CalypsoDrops {
    fun sync(c: Context): Int {
        if (!SealedSecret(c, Server.TOKEN_SLOT).has()) return 0
        val calypso = CalypsoCore.get(c).calypso
        val server = Server(c)
        val prefs = c.getSharedPreferences("calypso_drops", Context.MODE_PRIVATE)
        val pem = calypso.publicPem()
        if (prefs.getInt("pem.sent", 0) != pem.hashCode()) {
            server.postJson("/v1/calypso/key", JSONObject().put("pem", pem))
            prefs.edit().putInt("pem.sent", pem.hashCode()).apply()
        }
        val a = JSONObject(server.getJson("/v1/calypso/drops")).optJSONArray("drops") ?: return 0
        val drops = (0 until a.length()).mapNotNull { Envelope.parse(a.getJSONObject(it).toString()) }
        val got = drops.count { calypso.accept(it) }
        if (drops.isNotEmpty()) server.postJson("/v1/calypso/ack", JSONObject().put("ids", JSONArray(drops.map { it.id })))
        return got
    }
}
