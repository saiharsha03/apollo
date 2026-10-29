package dev.apollo.net

import android.content.Context
import dev.apollo.approvals.ApolloDb
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** A request NetGate refused. An IOException, so every caller's network-failure path handles it. */
class BlockedHostException(val host: String) : IOException("Blocked by NetGate: $host")

/**
 * The one door to the internet (Zeus's brain/NetGate.kt, kept simple). Every OkHttp client Apollo makes comes from
 * [builder], so every request passes [interceptor] first: https only, the default port, no user info, and one of
 * - the Apollo server (the address in settings, Server.baseUrl),
 * - api.cloudhost.example (Lemnos: reboot and power cycle, direct, so they work when the server is down),
 * - api.spotify.com (playlist changes he tapped Allow on: muse/SpotifyPlaylists),
 * - a host on his fetch_allow list (mini-app pages and Live updates; adding one is his choice in Approvals).
 * Anything else is refused before a connection opens and logged as blocked. Allowed requests are logged too
 * ([NetLog]: host and purpose only, never a path, query, header or body); the "What left the phone" screen shows it.
 * TLS goes through [TlsGuard]. Second layer: res/xml/network_security_config.xml turns cleartext off everywhere.
 * Not through here: Firebase's own registration call and FCM (Google Play services' process), Google Home and
 * Spotify App Remote (IPC to other apps), the system browser for links he taps.
 */
object NetGate {
    const val CLOUDHOST = "api.cloudhost.example"
    const val SPOTIFY = "api.spotify.com"
    @Volatile private var app: Context? = null

    fun init(c: Context) { app = c.applicationContext }

    /** Pure: a URL may leave only over https on the default port, without user info, to one of [hosts] exactly. */
    fun allows(url: String, hosts: Set<String>): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme.equals("https", ignoreCase = true) && (u.port == -1 || u.port == 443) && u.rawUserInfo == null && host in hosts
    }

    fun serverHost(c: Context): String? = runCatching { URI(Server.baseUrl(c)).host?.lowercase() }.getOrNull()

    fun hosts(c: Context): Set<String> =
        setOfNotNull(serverHost(c), CLOUDHOST, SPOTIFY) + runCatching { ApolloDb.get(c).fetchAllow().map { it.lowercase() } }.getOrDefault(emptyList())

    /** Pure: what a request is for, from its host and path. Only this ever reaches the log. */
    fun purpose(host: String, path: String, server: String?): String = when {
        host == server && path.startsWith("/v1/facts") -> "Facts: what Apollo sensed, for Athena"
        host == server && (path.startsWith("/v1/chat") || path.startsWith("/v1/messages")) -> "Chat: your words"
        host == server && path.startsWith("/v1/uploads") && path.length > "/v1/uploads".length + 1 -> "A file from chat (download)"
        host == server && path.startsWith("/v1/uploads") -> "A photo or file you sent"
        host == server && path.startsWith("/v1/backup") -> "Backup (sealed on the phone)"
        host == server && path.startsWith("/v1/calypso") -> "Calypso drops (sealed; the public key)"
        host == server && path.endsWith("_key") -> "A key you sent from Calypso"
        host == server && path.startsWith("/v1/pair") -> "Pairing"
        host == server && path.startsWith("/v1/import") -> "Zeus's mini apps (the move)"
        host == server -> "Apollo server (sync, apps, status)"
        host == CLOUDHOST -> "CloudHost: server power"
        host == SPOTIFY -> "Spotify: a playlist change you allowed"
        else -> "Mini app or Live update: $host"
    }

    /** Refuses before a connection is opened; logs allowed and blocked requests by host and purpose. */
    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val c = app
        val host = req.url.host.lowercase()
        val server = c?.let { serverHost(it) }
        if (c == null || !allows(req.url.toString(), hosts(c))) {
            c?.let { NetLog.get(it).add(host, "Blocked", req.method, 0, blocked = true) }
            dev.apollo.clio.Clio.warn("netgate", "blocked", host)
            throw BlockedHostException(host)
        }
        NetLog.get(c).add(host, purpose(host, req.url.encodedPath, server), req.method, req.body?.contentLength()?.coerceAtLeast(0) ?: 0)
        chain.proceed(req)
    }

    private val base: OkHttpClient by lazy {
        val ssl = TlsGuard.sslContext(null)
        OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, TlsGuard.trustManager).addInterceptor(interceptor)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Every client starts here. [keys]: a client certificate to offer (the server's mTLS, net/DeviceCert). */
    fun builder(keys: Array<KeyManager>? = null): OkHttpClient.Builder =
        if (keys == null) base.newBuilder() else TlsGuard.sslContext(keys).let { base.newBuilder().sslSocketFactory(it.socketFactory, TlsGuard.trustManager) }
}

/**
 * Refuses certificate chains issued by eMudhra (emSign), as Zeus did (brain/TlsGuard.kt, decision 2026-10-20: eight emSign
 * intermediate CA keys were compromised and none of Apollo's hosts use eMudhra, but Android's root store may keep emSign
 * for a while). After the platform's normal validation, a chain naming eMudhra or emSign fails the handshake.
 * Not pinning: the server's Let's Encrypt certificate rotates, so a pinned leaf would lock the phone out.
 */
object TlsGuard {
    private val BAD = listOf("emudhra", "emsign")

    fun tainted(chain: Array<out X509Certificate>?): Boolean = chain.orEmpty().any {
        val names = (it.subjectX500Principal.name + " " + it.issuerX500Principal.name).lowercase()
        BAD.any { b -> b in names }
    }

    private fun refuse(chain: Array<out X509Certificate>?, host: String?) {
        if (!tainted(chain)) return
        dev.apollo.clio.Clio.error("netgate", "refused", "eMudhra-issued certificate for ${host ?: "?"}")
        throw CertificateException("Refused eMudhra-issued certificate for ${host ?: "?"}")
    }

    class Guarded(private val delegate: X509TrustManager) : X509ExtendedTrustManager() {
        private fun ext() = delegate as? X509ExtendedTrustManager

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            ext()?.checkServerTrusted(chain, authType, socket) ?: delegate.checkServerTrusted(chain, authType)
            refuse(chain, socket?.inetAddress?.hostName)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            ext()?.checkServerTrusted(chain, authType, engine) ?: delegate.checkServerTrusted(chain, authType)
            refuse(chain, engine?.peerHost)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkServerTrusted(chain, authType)
            refuse(chain, null)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            ext()?.checkClientTrusted(chain, authType, socket) ?: delegate.checkClientTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            ext()?.checkClientTrusted(chain, authType, engine) ?: delegate.checkClientTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = delegate.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    val trustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        Guarded(tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager)
    }

    fun sslContext(keys: Array<KeyManager>?): SSLContext = SSLContext.getInstance("TLS").apply { init(keys, arrayOf(trustManager), null) }
}
