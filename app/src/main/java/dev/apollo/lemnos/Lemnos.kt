package dev.apollo.lemnos

import android.content.Context
import dev.apollo.calypso.SealedSecret
import dev.apollo.net.Server
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The VPS at a glance, from the Apollo server (server/apollo/lemnos.py). */
data class Health(val load: List<Double>, val memUsed: Long, val memTotal: Long, val diskUsed: Long, val diskTotal: Long, val uptime: Long, val units: Map<String, String>, val control: List<String> = emptyList())

/** One entry in a folder. */
data class Entry(val name: String, val dir: Boolean, val size: Long, val modified: Long)

/** A tmux session the phone may attach (the server never lists Iris's). */
data class Session(val name: String, val attached: Int, val activity: Long)

/** The remote desktop's unit: off, starting, running; [ready] when its socket is up. */
data class DesktopState(val state: String, val ready: Boolean, val memory: Long?)

/**
 * Lemnos, the phone side: the server's health and service restarts, its files (the workspace and the mini apps), a tmux
 * terminal, the remote desktop, and CloudHost's power controls. Server routes ride on the phone's device token like every Apollo route; CloudHost is called direct
 * from the phone with his token from Calypso (slot `cloudhost`, as in Zeus), so a reboot works when the server is down.
 * Blocking; call off the main thread.
 */
class Lemnos(private val c: Context) {
    private val server get() = Server(c)

    fun health(): Health {
        val j = JSONObject(server.getJson("/v1/lemnos/status"))
        val load = j.optJSONArray("load")
        val units = j.optJSONObject("units")
        return Health(
            (0 until (load?.length() ?: 0)).map { load!!.getDouble(it) }, j.optLong("mem_used"), j.optLong("mem_total"),
            j.optLong("disk_used"), j.optLong("disk_total"), j.optLong("uptime"),
            units?.keys()?.asSequence()?.associateWith { units.getString(it) }.orEmpty(),
            j.optJSONArray("control").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) } },
        )
    }

    /** Start, stop or restart a unit on root's list; answers its state after. */
    fun unit(name: String, action: String): String =
        JSONObject(server.postJson("/v1/lemnos/unit", JSONObject().put("unit", name).put("action", action))).optString("state")

    fun roots(): List<String> = JSONObject(server.getJson("/v1/lemnos/files")).optJSONArray("roots").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) } }

    fun list(root: String, path: String): List<Entry> {
        val a = JSONObject(server.getJson("/v1/lemnos/files", mapOf("root" to root, "path" to path))).optJSONArray("entries") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.getString("name"), it.optBoolean("dir"), it.optLong("size"), it.optLong("modified")) }
    }

    /** A text file's contents (the server refuses binary and anything over 1 MB). */
    fun text(root: String, path: String): String = JSONObject(server.getJson("/v1/lemnos/text", mapOf("root" to root, "path" to path))).optString("text")

    /** A new file (the server refuses to overwrite), or [replace] an existing text file with its edit. */
    fun put(root: String, path: String, bytes: ByteArray, replace: Boolean = false) =
        server.putBytes("/v1/lemnos/file", mapOf("root" to root, "path" to path, "replace" to replace.toString()), bytes)

    fun rename(root: String, path: String, to: String) { server.postJson("/v1/lemnos/rename", JSONObject().put("root", root).put("path", path).put("to", to)) }

    /** A new empty folder (same roots and names as an upload; the server refuses a taken name). */
    fun newFolder(root: String, path: String) { server.postJson("/v1/lemnos/folder", JSONObject().put("root", root).put("path", path)) }

    fun delete(root: String, path: String) { server.postJson("/v1/lemnos/delete", JSONObject().put("root", root).put("path", path)) }

    fun sessions(): List<Session> {
        val a = JSONObject(server.getJson("/v1/lemnos/sessions")).optJSONArray("sessions") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Session(it.getString("name"), it.optInt("attached"), it.optLong("activity")) }
    }

    /** A one-time challenge, the id of the server's active terminal key and its pending key's code (null: none). */
    fun challenge(): Triple<String, String?, String?> {
        val j = JSONObject(server.getJson("/v1/lemnos/challenge"))
        fun opt(k: String) = if (j.isNull(k)) null else j.optString(k).ifEmpty { null }
        return Triple(j.getString("nonce"), opt("key"), opt("pending"))
    }

    /** This phone's terminal key, pending until root approves it by the code answered; 409 once a key is active. */
    fun register(key: String, nonce: String, sig: String): String =
        JSONObject(server.postJson("/v1/lemnos/key", JSONObject().put("key", key).put("nonce", nonce).put("sig", sig))).getString("pending")

    /** A new shell and its one-time pass to the terminal (so one fingerprint opens it). */
    fun newShell(p: Proof): Pair<String, Map<String, String>> =
        JSONObject(server.postJson("/v1/lemnos/sessions", JSONObject(), p.query)).let { it.optString("session") to mapOf("ticket" to it.optString("ticket")) }

    fun closeShell(name: String, p: Proof) { server.deleteJson("/v1/lemnos/sessions/$name", p.query) }

    /** [pass]: a [Proof]'s query or a new shell's ticket, good for this one connection. */
    fun terminal(session: String, readOnly: Boolean, cols: Int, rows: Int, pass: Map<String, String>, l: WebSocketListener): WebSocket =
        server.webSocket("/v1/lemnos/term", mapOf("session" to session, "ro" to readOnly.toString(), "cols" to "$cols", "rows" to "$rows") + pass, l)

    private fun desktopState(j: JSONObject) = DesktopState(j.optString("state"), j.optBoolean("ready"), if (j.isNull("memory")) null else j.optLong("memory"))

    fun desktop(): DesktopState = desktopState(JSONObject(server.getJson("/v1/lemnos/desktop")))

    fun desktop(action: String): DesktopState = desktopState(JSONObject(server.postJson("/v1/lemnos/desktop", JSONObject().put("action", action))))

    fun desktopView(l: WebSocketListener): WebSocket = server.webSocket("/v1/lemnos/desktop/view", emptyMap(), l)
}

/** CloudHost Cloud, direct from the phone (Zeus's CloudHost.kt, the same slot and server). */
object CloudHost {
    private const val API = "https://api.cloudhost.example/v1"
    /** The VPS, picked out of the project by its address. */
    const val BOX_IP = "YOUR_SERVER_IP"
    const val SLOT = "cloudhost"

    data class Box(val id: Long, val name: String, val status: String, val ip: String, val type: String)

    /** reboot = ACPI restart; reset = power cycle (hard). */
    enum class Act(val path: String, val label: String) { REBOOT("reboot", "Reboot"), RESET("reset", "Power cycle"), POWER_ON("poweron", "Power on") }

    class Failed(message: String) : IOException(message)

    private val client = dev.apollo.net.NetGate.builder().followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun token(c: Context): String? = SealedSecret(c, SLOT).read()

    private fun run(token: String, path: String, post: Boolean = false): JSONObject {
        val b = Request.Builder().url(API + path).header("Authorization", "Bearer $token")
        if (post) b.post("{}".toRequestBody("application/json".toMediaType()))
        return client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw Failed(runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull()?.ifEmpty { null } ?: "CloudHost answered ${r.code}")
            JSONObject(body.ifEmpty { "{}" })
        }
    }

    fun box(token: String): Box? {
        val list = run(token, "/servers?per_page=50").optJSONArray("servers") ?: return null
        val all = (0 until list.length()).map { list.getJSONObject(it) }.map {
            Box(it.optLong("id"), it.optString("name"), it.optString("status"), it.optJSONObject("public_net")?.optJSONObject("ipv4")?.optString("ip").orEmpty(),
                it.optJSONObject("server_type")?.optString("name").orEmpty())
        }
        return all.firstOrNull { it.ip == BOX_IP } ?: all.singleOrNull()
    }

    /** Starts the action; CloudHost answers at once with its status ("running"). */
    fun act(token: String, id: Long, a: Act): String = run(token, "/servers/$id/actions/${a.path}", post = true).optJSONObject("action")?.optString("status").orEmpty()
}
