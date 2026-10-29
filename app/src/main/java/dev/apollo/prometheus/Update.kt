package dev.apollo.prometheus

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import dev.apollo.BuildConfig
import dev.apollo.clio.Clio
import dev.apollo.net.Server
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A newer Apollo the server offers. */
data class Release(val versionCode: Int, val versionName: String, val sha256: String, val size: Long, val notes: String)

/**
 * Prometheus, the phone side only: ask the server for a newer APK, and after his tap download it, check it (sha256 from
 * the server, the same package, a newer version, and signed by the same key as the installed Apollo), then hand it to
 * PackageInstaller, which shows Android's own confirm. Signing is never here: Owner's signing service on the VPS signs
 * after his fingerprint in the authenticator (server/deploy/apollo-release.sh); the server's /v1/update answers {} until a release.
 */
object Update {
    /** An offer counts when it is newer than this build and names its checksum. Pure. */
    fun offered(code: Int, sha256: String, current: Int): Boolean = code > current && Regex("^[0-9a-f]{64}$").matches(sha256)

    /** The offer when it is newer than this build; null otherwise (the stub's empty answer included). */
    fun newer(j: JSONObject, current: Int = BuildConfig.VERSION_CODE): Release? {
        val code = j.optInt("version_code", 0)
        val sha = j.optString("sha256")
        if (!offered(code, sha, current)) return null
        return Release(code, j.optString("version_name", code.toString()), sha, j.optLong("size"), j.optString("notes"))
    }

    /** Blocking. */
    fun check(c: Context): Release? = newer(JSONObject(Server(c).getJson("/v1/update").ifBlank { "{}" }))

    /** Blocking: download, check, install. Android then asks him to confirm (and to allow Apollo to install apps, once). */
    fun install(c: Context, r: Release) {
        val apk = File(c.cacheDir, "apollo-update.apk")
        try {
            Server(c).download("/v1/update/apk", mapOf("v" to r.versionCode.toString()), apk)
            val sha = apk.inputStream().use { s -> MessageDigest.getInstance("SHA-256").let { d -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; d.update(b, 0, n) }; d.digest() } }
                .joinToString("") { "%02x".format(it) }
            check(sha == r.sha256) { "the download doesn't match its checksum" }
            val pm = c.packageManager
            val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("not an APK")
            check(info.packageName == c.packageName) { "a different app" }
            check(info.longVersionCode > BuildConfig.VERSION_CODE) { "not newer" }
            val mine = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
            val theirs = info.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
            check(mine != null && mine == theirs) { "signed by a different key" }
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(c.packageName) }
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("apollo.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val done = PendingIntent.getBroadcast(c, id, Intent(c, UpdateReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(done.intentSender)
            }
            Clio.info("prometheus", "install", "v${r.versionCode} handed to the installer")
        } finally { apk.delete() }
    }
}

/** The installer's answer: its confirm screen (shown over Apollo), or how it went. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Clio.info("prometheus", "installed")
            else -> Clio.warn("prometheus", "install", "status $status: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(120)}")
        }
    }
}
