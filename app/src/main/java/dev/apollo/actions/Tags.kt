package dev.apollo.actions

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import dev.apollo.clio.Clio
import dev.apollo.net.Facts
import org.json.JSONObject
import java.security.SecureRandom

/**
 * NFC tags (stickers at his bed, door, desk). He adds one in More > Phone and Apollo writes a link with a random id to it.
 * A tap is only a fact (`nfc`, with the tag's name), so Athena gives tags jobs as routine rows (when_trigger nfc:bed). A tag
 * never calls or sends anything: anyone could tap a copy. An id is 128 random bits, so it can't be guessed.
 */
object Tags {
    const val SCHEME = "apollo-tag"
    private fun prefs(c: Context) = c.getSharedPreferences("nfc_tags", Context.MODE_PRIVATE)

    /** id to his name for it. */
    fun all(c: Context): Map<String, String> = prefs(c).all.mapValues { it.value.toString() }

    fun newId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Pure: the tag id in a link Apollo wrote, or null for anything else. */
    fun idOf(uri: Uri?): String? = uri?.takeIf { it.scheme == SCHEME && it.host == "t" }?.lastPathSegment?.takeIf { Regex("[0-9a-f]{32}").matches(it) }

    fun remove(c: Context, id: String) = prefs(c).edit().remove(id).apply()

    /** The next tag he holds to the phone gets the link; [done] gets null or what went wrong. Kept once written. */
    fun write(a: Activity, name: String, done: (String?) -> Unit) {
        val nfc = NfcAdapter.getDefaultAdapter(a) ?: return done("This phone has no NFC.")
        if (!nfc.isEnabled) return done("NFC is off in the phone's settings.")
        val id = newId()
        val msg = NdefMessage(arrayOf(NdefRecord.createUri("$SCHEME://t/$id")))
        nfc.enableReaderMode(a, { tag ->
            val err = runCatching {
                Ndef.get(tag)?.use { n -> n.connect(); if (!n.isWritable) error("This tag is locked."); if (n.maxSize < msg.byteArrayLength) error("This tag is too small."); n.writeNdefMessage(msg) }
                    ?: NdefFormatable.get(tag)?.use { f -> f.connect(); f.format(msg) } ?: error("This tag can't hold a link.")
            }.exceptionOrNull()?.let { it.message ?: "Couldn't write the tag." }
            if (err == null) {
                prefs(a).edit().putString(id, name.trim().lowercase().take(30)).apply()
                Facts.post(a, "nfc", JSONObject().put("tag", name.trim().lowercase()).put("added", true))
            }
            a.runOnUiThread { runCatching { nfc.disableReaderMode(a) }; done(err) }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
    }

    fun stopWrite(a: Activity) { runCatching { NfcAdapter.getDefaultAdapter(a)?.disableReaderMode(a) } }
}

/** A tap on one of his tags: the `nfc` fact (urgent, so a routine on it runs at once), nothing else. Unknown tags do nothing. */
class TagActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only the NFC stack's own discovery (with its tag) counts; an app starting this screen with a link doesn't.
        val real = intent?.action == android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED && intent?.hasExtra(android.nfc.NfcAdapter.EXTRA_TAG) == true
        val name = Tags.idOf(intent?.data)?.takeIf { real }?.let { Tags.all(this)[it] }
        if (name != null) {
            Clio.info("nfc", "he tapped a tag", name)
            Facts.post(this, "nfc", JSONObject().put("tag", name), urgent = true)
        }
        finish()
    }
}
