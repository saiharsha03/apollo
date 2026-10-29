package dev.apollo.prometheus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.apollo.BuildConfig
import dev.apollo.ui.Action
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Updates: this build, and a newer one when the server offers it (installed only on his tap). */
@Composable
fun UpdateScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Release?>(null) }
    var line by remember { mutableStateOf<String?>("Checking") }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { runCatching { Update.check(c) } }
            .onSuccess { release = it; line = if (it == null) "Up to date. (Signed updates arrive once the signing service is running.)" else null }
            .onFailure { line = "Can't reach the server: ${it.message ?: it.javaClass.simpleName}" }
    }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Updates", style = Type.title)
        Text("This is Apollo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}).", style = Type.body)
        release?.let { r ->
            Text("Apollo ${r.versionName} is ready.", style = Type.heading)
            if (r.notes.isNotBlank()) Text(r.notes, style = Type.small)
            Action("Install") {
                line = "Downloading"
                scope.launch { line = withContext(Dispatchers.IO) { runCatching { Update.install(c, r) }.fold({ "Android will ask you to confirm." }, { "Not installed: ${it.message}" }) } }
            }
        }
        line?.let { Text(it, style = Type.small) }
        Action("Check again", primary = false) { line = "Checking"; tick++ }
    }
}
