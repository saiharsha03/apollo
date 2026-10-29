package dev.apollo.actions

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import dev.apollo.hermes.Commands

/**
 * How a call or a message to someone goes out when nobody says (Owner's rule, from Zeus's CallRouting): an Indian
 * number (+91) on WhatsApp, video first; a US number (+1) and everything else on the phone. Explicit words win. Pure.
 */
object CallWay {
    const val VIDEO = "whatsapp_video"
    const val VOICE = "whatsapp_voice"
    const val PHONE = "phone"

    /** "IN", "US" or "OTHER". A bare 10-digit number is never guessed as Indian (US area codes start with 6-9 too: 716). */
    fun country(number: String, region: String): String {
        val digits = number.filter { it.isDigit() }
        val plus = number.trim().startsWith("+")
        return when {
            (plus && digits.startsWith("91")) || digits.startsWith("0091") -> "IN"
            !plus && digits.length == 12 && digits.startsWith("91") && digits[2] in '6'..'9' -> "IN"
            (plus && digits.startsWith("1")) || (digits.length == 11 && digits.startsWith("1")) -> "US"
            plus -> "OTHER"
            region == "IN" -> "IN"
            else -> if (region in setOf("US", "CA")) "US" else "OTHER"
        }
    }

    /** "video", "whatsapp", "phone call"... to one of the three; null (or anything else) is auto. */
    fun via(word: String?): String? = when (word?.trim()?.lowercase()?.replace(' ', '_')) {
        VIDEO, "video", "video_call", "whatsapp_video_call" -> VIDEO
        VOICE, "voice", "whatsapp", "whatsapp_call", "whatsapp_voice_call" -> VOICE
        PHONE, "phone_call", "call", "dialer", "cell" -> PHONE
        else -> null
    }

    /** The way a call goes: his word, else by the number's country. */
    fun pick(word: String?, country: String): String = via(word) ?: if (country == "IN") VIDEO else PHONE
}

/** A contact as the call and message actions need it. */
data class Person(val id: Long?, val name: String, val number: String?)

/**
 * His phone book, for "call Amma" and "text mom". Reads only the one contact asked for; nothing leaves the phone except
 * into the intent that starts the call or opens the chat (which he then sends himself).
 */
object People {
    private const val WA = "com.whatsapp"
    private const val WA_VOICE = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    private const val WA_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"

    fun canRead(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** A number (when [who] is one) or the one contact whose name clearly matches; null when none or it isn't clear which. */
    fun find(c: Context, who: String): Person? {
        val w = who.trim()
        if (w.isEmpty()) return null
        if (w.count { it.isDigit() } >= 7 && w.all { it.isDigit() || it in "+-() ." }) return Person(null, w, w)
        if (!canRead(c)) return null
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val found = c.contentResolver.query(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(w)), cols, null, null,
            "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC")?.use { cur ->
            buildMap { while (cur.moveToNext()) { val n = cur.getString(1) ?: continue; if (n !in this) put(n, Person(cur.getLong(0), n, cur.getString(2))) } }
        }.orEmpty()
        val name = Commands.pick(w, found.keys.toList()) ?: return null
        return found.getValue(name)
    }

    fun region(c: Context): String {
        val tm = c.getSystemService(TelephonyManager::class.java)
        return tm?.networkCountryIso.orEmpty().ifEmpty { tm?.simCountryIso.orEmpty() }.uppercase().ifEmpty { "US" }
    }

    fun country(c: Context, p: Person): String = p.number?.let { CallWay.country(it, region(c)) } ?: "OTHER"

    /** WhatsApp's own call row for the contact (VIEW on it starts the call). Not a public API, but on most installs. */
    private fun waRow(c: Context, contactId: Long, mime: String): Long? = runCatching {
        c.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?", arrayOf("$contactId", mime), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()

    /** The intent that places the call [via], or null with why not. No CALL_PHONE: the dialer opens and he presses call. */
    fun callIntent(c: Context, p: Person, via: String): Pair<Intent?, String> {
        fun wa(mime: String) = p.id?.let { waRow(c, it, mime) }?.let {
            Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, it), mime).setPackage(WA)
        }
        return when (via) {
            CallWay.VIDEO -> (wa(WA_VIDEO) ?: wa(WA_VOICE))?.let { it to "WhatsApp video calling ${p.name}" } ?: dial(p)
            CallWay.VOICE -> wa(WA_VOICE)?.let { it to "WhatsApp calling ${p.name}" } ?: dial(p)
            else -> dial(p)
        }
    }

    private fun dial(p: Person): Pair<Intent?, String> =
        p.number?.let { Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(it))) to "dialer open for ${p.name}" } ?: (null to "${p.name} has no phone number")

    /** The chat with [text] filled in: WhatsApp (wa.me) or Messages. He presses send there. */
    fun messageIntent(c: Context, p: Person, text: String, whatsapp: Boolean): Pair<Intent?, String> {
        val n = p.number ?: return null to "${p.name} has no phone number"
        if (whatsapp) {
            val e164 = PhoneNumberUtils.formatNumberToE164(n, region(c)) ?: n
            val digits = e164.filter { it.isDigit() }
            return Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(text))).setPackage(WA) to "WhatsApp open with ${p.name}; you press send"
        }
        return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).putExtra("sms_body", text) to "Messages open for ${p.name}; you press send"
    }
}
