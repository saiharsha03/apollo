package dev.apollo.senses

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.apollo.ui.Row2
import dev.apollo.ui.Type
import org.json.JSONObject

/**
 * The text senders he says are his bank (Zeus's confirmed-sender check). Each change is a `bank_sender` fact: only his
 * phone posts facts, so only he can make a sender a bank. The server then sends that sender's texts to Iris too, and
 * rings him when one says fraud words. Kept here too, to list them.
 */
object BankSenders {
    private fun prefs(c: Context) = c.getSharedPreferences("senses_bank_senders", Context.MODE_PRIVATE)
    fun list(c: Context): Set<String> = prefs(c).getStringSet("all", emptySet()).orEmpty()

    fun set(c: Context, sender: String, on: Boolean) {
        val s = sender.trim().take(60).ifEmpty { return }
        prefs(c).edit().putStringSet("all", if (on) list(c) + s else list(c) - s).apply()
        Senses.sink.post("bank_sender", JSONObject().put("sender", s).put("on", on), false)
    }
}

@Composable
fun BankSendersSection() {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    Text("BANK TEXTS", style = Type.kicker)
    Text("Senders of your bank's texts, exactly as Messages shows them (a short code or a name). Their texts also go to Iris, and fraud words ring you.",
        style = Type.small)
    OutlinedTextField(typed, { typed = it }, label = { Text("Sender") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row2("Add sender", typed.trim().ifEmpty { null }, "Add") { if (typed.isNotBlank()) { BankSenders.set(c, typed, true); typed = ""; tick++ } }
    remember(tick) { BankSenders.list(c).sorted() }.forEach { s -> Row2(s, "Tap to remove", "Remove") { BankSenders.set(c, s, false); tick++ } }
}
