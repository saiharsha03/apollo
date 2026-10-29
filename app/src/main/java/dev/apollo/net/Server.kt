package dev.apollo.net

import android.content.Context
import dev.apollo.calypso.SealedSecret
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A server call that didn't come back 2xx. [code] is 0 when the network failed before an answer. */
class ServerError(val code: Int, message: String) : IOException(message)

/**
 * The Apollo server's phone routes (docs/API.md), over one OkHttp client. The device token is sealed in Calypso's keys
 * (slot `apollo_device`) and read per call, so a rotated token takes effect at once. Blocking: call off the main thread.
 */
class Server(private val context: Context, private val base: String = baseUrl(context)) {

    private fun token(): String = SealedSecret(context, TOKEN_SLOT).read() ?: throw ServerError(401, "No device token in Calypso (slot $TOKEN_SLOT)")

    private fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null): String {
        val url = (base.trimEnd('/') + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val rb = body?.toRequestBody(JSON)
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${token()}").method(method, if (method == "GET") null else rb ?: "".toRequestBody(JSON)).build()
        try {
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ServerError(r.code, "$method $path: ${r.code}")
                return text
            }
        } catch (e: ServerError) { throw e } catch (e: IOException) { throw ServerError(0, "$method $path: ${e.javaClass.simpleName}") }
    }

    private fun bytes(path: String, query: Map<String, String>): ByteArray {
        val url = (base.trimEnd('/') + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${token()}").get().build()
        try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ServerError(r.code, "GET $path: ${r.code}")
                return r.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: ServerError) { throw e } catch (e: IOException) { throw ServerError(0, "GET $path: ${e.javaClass.simpleName}") }
    }

    /**
     * Pairing: the phone makes its own token, seals it in Calypso, and sends it once with the one-time code root printed on
     * the server (deploy/pair.sh). The server keeps only its hash, and hands back the phone's client certificate.
     */
    fun pair(code: String): Boolean {
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        // With it, a request for the phone's client certificate (mTLS); the server signs it when its device CA exists.
        val csr = runCatching { DeviceCert.request() }.getOrNull()
        val body = JSONObject().put("code", code.trim().lowercase()).put("token", token).apply { csr?.let { put("csr", it) } }.toString().toRequestBody(JSON)
        val req = Request.Builder().url(base.trimEnd('/') + "/v1/pair").post(body).build()
        val answer = runCatching { client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else null } }.getOrNull() ?: return false
        SealedSecret(context, TOKEN_SLOT).save(token)
        runCatching { JSONObject(answer).optString("cert").takeIf { it.isNotEmpty() }?.let(DeviceCert::adopt) }
            .onFailure { dev.apollo.clio.Clio.warn("pair", "certificate not kept", it.javaClass.simpleName) }
        return true
    }

    /** Hands Athena's API key (Calypso's keys, slot "anthropic") to the server once, after his fingerprint. */
    fun sendBrainKey(key: String) { call("POST", "/v1/brain_key", body = JSONObject().put("key", key).toString()) }

    /** The admin key (Calypso slot anthropic_admin): the Usage page's real costs. */
    fun sendAdminKey(key: String) { call("POST", "/v1/admin_key", body = JSONObject().put("key", key).toString()) }

    /** Owner's TomTom key (Calypso slot tomtom): the agents' tomtom tool (live traffic, place names). */
    fun sendTomTomKey(key: String) { call("POST", "/v1/tomtom_key", body = JSONObject().put("key", key).toString()) }

    /** His 511NY key (Calypso slot ny511): the agents' roads tool (NY crashes, closures, road work). */
    fun sendNy511Key(key: String) { call("POST", "/v1/ny511_key", body = JSONObject().put("key", key).toString()) }

    /** The self-hosted environment's key, pasted once from the Console: only the doorman box on the server reads it. */
    fun sendBoxKey(key: String) { call("POST", "/v1/box_key", body = JSONObject().put("key", key).toString()) }

    /** Zeus's old mini apps (a zip) up to the server once; the server keeps it for the move, untouched. */
    fun importZeusApps(zip: ByteArray) {
        val req = Request.Builder().url(base.trimEnd('/') + "/v1/import/zeus-apps").header("Authorization", "Bearer ${token()}")
            .post(zip.toRequestBody("application/zip".toMediaType())).build()
        client.newCall(req).execute().use { if (!it.isSuccessful) throw ServerError(it.code, "import: ${it.code}") }
    }

    /** Zeus's Spotify sign-in (sealed in Calypso), once after his fingerprint: the server's spotify tool refreshes it from then on. */
    fun sendSpotifyTokens(tokens: JSONObject) { call("POST", "/v1/spotify_tokens", body = tokens.toString()) }

    /** Clio's new lines, the daily upload (a JSON list). */
    fun postClio(lines: JSONArray) { call("POST", "/v1/clio", body = lines.toString()) }

    /** Pan (Plazmi): his words, limits, move-in, claim, confirm and key. Each answers the server's JSON. */
    fun pan(method: String, path: String = "", body: JSONObject? = null): JSONObject = JSONObject(call(method, "/v1/pan$path", body = body?.toString()).ifBlank { "{}" })

    /** Zeus's phone data, once (ZeusData): returns what the server added. */
    fun importZeusData(json: ByteArray): JSONObject {
        val req = Request.Builder().url(base.trimEnd('/') + "/v1/import/zeus-data").header("Authorization", "Bearer ${token()}")
            .post(json.toRequestBody(JSON)).build()
        try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ServerError(r.code, "zeus data: ${r.code}")
                return JSONObject(r.body?.string().orEmpty())
            }
        } catch (e: ServerError) { throw e } catch (e: IOException) { throw ServerError(0, "zeus data: ${e.javaClass.simpleName}") }
    }

    fun postFacts(facts: JSONArray) { call("POST", "/v1/facts", body = JSONObject().put("facts", facts).toString()) }

    fun sync(since: Long): JSONObject = JSONObject(call("GET", "/v1/sync", mapOf("since" to since.toString())))

    fun ack(ids: List<String>) { call("POST", "/v1/ack", body = JSONObject().put("ids", JSONArray(ids)).toString()) }

    /** Sends a chat message; a null [thread] starts a new one titled [title]. Returns (thread, message). */
    /** A message: into [thread] (the server routes it by the thread's agent, so no [to]), or a new thread for [to]. */
    /** source: "voice" when the text is a transcript (the server tags it for the agent). */
    fun chat(thread: String?, title: String, text: String, to: String?, replyTo: String? = null, upload: String? = null, source: String? = null): Pair<String, String> {
        val body = JSONObject().put("thread", thread ?: JSONObject.NULL).put("title", title).put("text", text)
        source?.let { body.put("source", it) }
        if (thread == null && to != null) body.put("to", to)
        replyTo?.let { body.put("reply_to", it.toLong()) }
        upload?.let { body.put("upload", it.toLong()) }
        val r = JSONObject(call("POST", "/v1/chat", body = body.toString()))
        return r.get("thread").toString() to r.get("message").toString()
    }

    fun react(message: String, emoji: String, on: Boolean) { call("POST", "/v1/messages/$message/react", body = JSONObject().put("emoji", emoji).put("on", on).toString()) }

    fun editMessage(message: String, text: String) { call("POST", "/v1/messages/$message/edit", body = JSONObject().put("text", text).toString()) }

    fun deleteMessage(message: String) { call("POST", "/v1/messages/$message/delete") }

    /** His photo or file up to the server; returns the upload id for [chat]. */
    fun upload(bytes: ByteArray, name: String, mime: String): String {
        val url = (base.trimEnd('/') + "/v1/uploads").toHttpUrl().newBuilder().addQueryParameter("name", name).build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${token()}").post(bytes.toRequestBody(mime.toMediaType())).build()
        try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ServerError(r.code, "upload: ${r.code}")
                return JSONObject(r.body?.string().orEmpty()).get("upload").toString()
            }
        } catch (e: ServerError) { throw e } catch (e: IOException) { throw ServerError(0, "upload: ${e.javaClass.simpleName}") }
    }

    fun uploadBytes(id: String): ByteArray = bytes("/v1/uploads/$id", emptyMap())

    /** A dark street map tile (the server fetches TomTom's with its own key): PNG bytes. */
    fun tile(z: Int, x: Int, y: Int): ByteArray = bytes("/v1/tiles/$z/$x/$y.png", emptyMap())

    fun threads(): JSONArray = JSONArray(call("GET", "/v1/threads"))

    /** His archive: put a conversation away, or bring it back. */
    fun archive(thread: String, on: Boolean) { call("POST", "/v1/threads/$thread/${if (on) "archive" else "restore"}") }

    /** Oldest first: after [since], else the newest [limit] or the [limit] just older than [before]. */
    fun thread(id: String, since: String?, before: String? = null, limit: Int? = null): JSONArray = JSONArray(call("GET", "/v1/threads/$id",
        listOfNotNull(since?.let { "since" to it }, before?.let { "before" to it }, limit?.let { "limit" to it.toString() }).toMap()))

    fun manifest(): JSONArray = JSONArray(call("GET", "/v1/apps/manifest"))

    fun appFile(app: String, path: String): ByteArray = bytes("/v1/apps/file", mapOf("app" to app, "path" to path))

    fun actionResult(id: String, ok: Boolean, detail: String) { call("POST", "/v1/actions/$id", body = JSONObject().put("ok", ok).put("detail", detail).toString()) }

    fun status(): JSONObject = JSONObject(call("GET", "/v1/status"))

    /** The latest activity per agent (the pixel characters), polled while Apollo is on screen. */
    fun activity(): JSONObject = JSONObject(call("GET", "/v1/activity"))

    fun fcm(token: String) { call("POST", "/v1/fcm", body = JSONObject().put("token", token).toString()) }

    // Routes that live with their feature (Lemnos, Mnemosyne, Prometheus): a JSON GET, and files streamed both ways.
    fun getJson(path: String, query: Map<String, String> = emptyMap()): String = call("GET", path, query)

    fun postJson(path: String, body: JSONObject, query: Map<String, String> = emptyMap()): String = call("POST", path, query, body.toString())

    fun download(path: String, query: Map<String, String>, to: java.io.File) {
        val url = (base.trimEnd('/') + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${token()}").get().build()).execute().use { r ->
            if (!r.isSuccessful) throw ServerError(r.code, "GET $path: ${r.code}")
            to.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
        }
    }

    fun upload(path: String, from: java.io.File) {
        val body = okhttp3.RequestBody.Companion.run { from.asRequestBody("application/octet-stream".toMediaType()) }
        client.newCall(Request.Builder().url(base.trimEnd('/') + path).header("Authorization", "Bearer ${token()}").put(body).build()).execute()
            .use { if (!it.isSuccessful) throw ServerError(it.code, "PUT $path: ${it.code}") }
    }

    fun deleteJson(path: String, query: Map<String, String> = emptyMap()): String = call("DELETE", path, query)

    /** Raw bytes to a route with a query (Lemnos's file upload and text save). */
    fun putBytes(path: String, query: Map<String, String>, bytes: ByteArray) {
        val url = (base.trimEnd('/') + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${token()}").put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute()
            .use { if (!it.isSuccessful) throw ServerError(it.code, "PUT $path: ${it.code}") }
    }

    /** Bytes to a route and bytes back, given up after [waitMs] (Sarvam voice: the phone falls back to its own voice). */
    fun postBytes(path: String, body: ByteArray, mime: String, waitMs: Long): ByteArray {
        val req = Request.Builder().url(base.trimEnd('/') + path).header("Authorization", "Bearer ${token()}").post(body.toRequestBody(mime.toMediaType())).build()
        try {
            client.newBuilder().callTimeout(waitMs, TimeUnit.MILLISECONDS).build().newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ServerError(r.code, "POST $path: ${r.code}")
                return r.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: ServerError) { throw e } catch (e: IOException) { throw ServerError(0, "POST $path: ${e.javaClass.simpleName}") }
    }

    /** A WebSocket on the same client and token (Lemnos's terminal and desktop). */
    fun webSocket(path: String, query: Map<String, String>, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
        val url = (base.trimEnd('/') + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return sockets.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer ${token()}").build(), listener)
    }

    companion object {
        const val DEFAULT_BASE = "https://apollo.example.com"
        const val TOKEN_SLOT = "apollo_device"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        // Through NetGate; offers the phone's client certificate once pairing issued one (net/DeviceCert, the server's mTLS).
        private val client by lazy { NetGate.builder(arrayOf(DeviceCert.keyManager)).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
        /** No read timeout (a quiet terminal is not a dead one); pings find the dead ones. */
        private val sockets by lazy { client.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build() }

        /** The server address: the default, or one he set in settings (prefs "server", key "base"). */
        fun baseUrl(c: Context): String = c.getSharedPreferences("server", Context.MODE_PRIVATE).getString("base", null) ?: DEFAULT_BASE

        fun setBaseUrl(c: Context, url: String?) = c.getSharedPreferences("server", Context.MODE_PRIVATE).edit().apply { if (url.isNullOrBlank()) remove("base") else putString("base", url.trim()) }.apply()
    }
}
