package dev.apollo.calypso

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** BiometricPrompt: strong (Class 3) biometrics, or the device PIN as the fallback. A crypto prompt authorises that one cipher. */
class AndroidBiometric(private val context: Context) : Biometric {
    override fun authenticate(title: String, subtitle: String, crypto: Unwrapper?, onOk: () -> Unit, onFail: (String) -> Unit) =
        prompt(title, subtitle, (crypto as? CipherUnwrapper)?.cipher?.let { BiometricPrompt.CryptoObject(it) }, onOk, onFail)

    /** The prompt authorises one use of [signature] (a key with auth timeout 0, such as Lemnos's terminal key). */
    fun sign(title: String, subtitle: String, signature: java.security.Signature, onOk: () -> Unit, onFail: (String) -> Unit) =
        prompt(title, subtitle, BiometricPrompt.CryptoObject(signature), onOk, onFail)

    private fun prompt(title: String, subtitle: String, crypto: BiometricPrompt.CryptoObject?, onOk: () -> Unit, onFail: (String) -> Unit) {
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title.take(60))
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        }
        if (crypto != null) prompt.authenticate(crypto, CancellationSignal(), context.mainExecutor, cb)
        else prompt.authenticate(CancellationSignal(), context.mainExecutor, cb)
    }
}

/**
 * Copies marked sensitive (no clipboard preview) and clears 30 s later, unless a newer Apollo copy replaced it. A
 * main-thread timer clears while the process lives; [ClipClearWorker] clears if it died.
 */
object SecureClipboard {
    const val CLEAR_MS = 30_000L
    private const val WORK = "secure-clipboard-clear"
    private fun prefs(c: Context) = c.getSharedPreferences("secure_clip", Context.MODE_PRIVATE)

    fun copy(context: Context, label: String, text: String) {
        val app = context.applicationContext
        val cm = app.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Apollo: $label", text).apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        })
        val nonce = java.util.UUID.randomUUID().toString()
        prefs(app).edit().putString("pending", nonce).commit()
        Handler(Looper.getMainLooper()).postDelayed({ clear(app, nonce) }, CLEAR_MS)
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ClipClearWorker>().setInitialDelay(CLEAR_MS, TimeUnit.MILLISECONDS).setInputData(workDataOf("nonce" to nonce)).build())
    }

    fun clear(c: Context, nonce: String) {
        val p = prefs(c)
        if (p.getString("pending", null) != nonce) return
        runCatching { c.getSystemService(ClipboardManager::class.java)!!.clearPrimaryClip() }
        p.edit().remove("pending").apply()
    }
}

class ClipClearWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        inputData.getString("nonce")?.let { SecureClipboard.clear(applicationContext, it) }
        return Result.success()
    }
}

/** One vault per process. Agents only ever get [Calypso.drop] and [Aegis]'s reads. Call the IO methods off the main thread. */
class CalypsoCore private constructor(app: Context) {
    // Calypso keeps the Keystore-direct format: its sealing stays exactly as Zeus left it.
    val db = VaultDb(app, KeystoreSealer(ALIAS, envelope = false))
    val calypso = Calypso(db, KeystoreBoxKeys())
    val aegis = Aegis(db)

    companion object {
        /** The vault's store key (no fingerprint; the box key is separate). Zeus's alias, kept. */
        const val ALIAS = "zeus.aegis.vault"
        @Volatile private var instance: CalypsoCore? = null
        fun get(context: Context): CalypsoCore = instance ?: synchronized(this) {
            instance ?: CalypsoCore(context.applicationContext).also { instance = it }
        }
    }
}
