package dev.apollo.calypso

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * FLAG_SECURE while this screen is showing: no screenshots, no screen recording, a blank recents preview. Counted, so
 * two secure screens at once (a money page over Approvals) don't clear each other's flag.
 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val w = context.activity()?.window
        if (w != null && SecureCount.up(w.hashCode())) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (w != null && SecureCount.down(w.hashCode())) w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** How many secure screens each window has open. up: true for the first; down: true when the last one closed. */
object SecureCount {
    private val n = HashMap<Int, Int>()
    @Synchronized fun up(w: Int): Boolean { val k = (n[w] ?: 0) + 1; n[w] = k; return k == 1 }
    @Synchronized fun down(w: Int): Boolean { val k = (n[w] ?: 1) - 1; if (k <= 0) n.remove(w) else n[w] = k; return k <= 0 }
}

tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
