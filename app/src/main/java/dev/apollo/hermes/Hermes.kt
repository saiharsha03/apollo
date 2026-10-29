package dev.apollo.hermes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.app.SearchManager
import android.provider.MediaStore
import android.view.KeyEvent
import dev.apollo.actions.CallWay
import dev.apollo.actions.People
import dev.apollo.actions.PhoneActions
import dev.apollo.clio.Clio
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Hermes says after a command. [needs] is a runtime permission to ask for before trying again; [undo] puts it back, where it can. */
data class Said(val text: String, val ok: Boolean = true, val needs: String? = null, val undo: ((Context) -> Unit)? = null)

/**
 * Runs his commands. They are his own words, so they run at once, except a call: that reaches someone else, so it goes
 * through PhoneActions and his approvals table like any other call_person. Blocking; call off the main thread.
 */
object Hermes {
    /** Null when [text] is not a command: the caller hands it to Athena. */
    /** The last command, for "no, I meant ...". Memory only. */
    @Volatile private var last: Cmd? = null

    fun handle(c: Context, text: String): Said? {
        val cmd = Commands.parse(text) ?: return null
        if (cmd !is Correction) last = cmd
        val said = runCatching { run(c, cmd) }.getOrElse { Said("That didn't work", ok = false) }
        Clio.info("hermes", cmd.kind, if (said.ok) "ran" else "failed")
        return said
    }

    private fun run(c: Context, cmd: Cmd): Said = when (cmd) {
        is Alarm -> PhoneActions.perform(c, "alarm", JSONObject().put("hour", cmd.hour).put("minute", cmd.minute)).said("Alarm set for %d:%02d".format(cmd.hour, cmd.minute))
        is Timer -> PhoneActions.perform(c, "timer", JSONObject().put("seconds", cmd.seconds)).said("Timer for ${minutes(cmd.seconds)}")
        is Torch -> torch(c, cmd.on)
        is Volume -> volume(c, cmd)
        is Media -> media(c, cmd.action)
        is Call -> call(c, cmd.who, cmd.via)
        is Navigate -> start(c, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(cmd.place))), "Directions to ${cmd.place}")
        is Open -> open(c, cmd.target)
        is Remind -> remind(c, cmd)
        is Play -> start(c, Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage("com.spotify.music").putExtra(SearchManager.QUERY, cmd.query), "Playing ${cmd.query} on Spotify")
        is Agenda -> agenda(c, cmd.day)
        is Dnd -> PhoneActions.perform(c, "dnd", JSONObject().put("on", cmd.on)).said("Do Not Disturb ${if (cmd.on) "on" else "off"}")
            .let { if (it.ok) it.copy(undo = { ctx -> PhoneActions.perform(ctx, "dnd", JSONObject().put("on", !cmd.on)) }) else it }
        is Message -> message(c, cmd)
        is Correction -> correct(c, cmd.target)
        is Toggle -> toggle(c, cmd)
    }

    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** Held on the phone by Later, so it shows on time offline. */
    private fun remind(c: Context, r: Remind): Said {
        val now = ZonedDateTime.now()
        val at = r.seconds?.let { now.plusSeconds(it.toLong()) } ?: run {
            var t = now.withHour(r.hour!!).withMinute(r.minute!!).withSecond(0).withNano(0)
            if (r.tomorrow) t = t.plusDays(1) else if (!t.isAfter(now)) t = t.plusDays(1)
            t
        }
        val day = if (at.toLocalDate() == now.toLocalDate()) "" else if (at.toLocalDate() == now.toLocalDate().plusDays(1)) " tomorrow" else " " + at.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        return PhoneActions.perform(c, "reminder", JSONObject().put("text", r.what).put("at", at.toInstant().toEpochMilli())).said("I'll remind you at ${CLOCK.format(at)}$day")
    }

    private fun agenda(c: Context, day: String): Said {
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            return Said("Apollo needs your calendar for that", ok = false, needs = Manifest.permission.READ_CALENDAR)
        val today = LocalDate.now()
        val d = when (day) {
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            else -> (1..7).map { today.plusDays(it.toLong()) }.first { it.dayOfWeek.name.equals(day, true) }
        }
        val zone = ZoneId.systemDefault()
        val events = dev.apollo.senses.CalendarSense.events(c, d.atStartOfDay(zone).toInstant().toEpochMilli(), d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val label = if (d == today) "today" else if (d == today.plusDays(1)) "tomorrow" else d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        if (events.isEmpty()) return Said("Nothing on your calendar $label")
        return Said("$label:\n" + events.joinToString("\n") { e -> (if (e.allDay) "All day" else CLOCK.format(java.time.Instant.ofEpochMilli(e.start).atZone(zone))) + "  " + e.title })
    }

    /** "text mom running late": like a call, it reaches someone else, so it waits for his tap (approvals row `message`). */
    private fun message(c: Context, m: Message): Said {
        val body = m.body ?: return Said("What should it say? Say: text ${m.who} and the message", ok = false)
        if (!People.canRead(c)) return Said("Apollo needs your contacts to text ${m.who}", ok = false, needs = Manifest.permission.READ_CONTACTS)
        val who = Book.get(c, m.who)
        val p = People.find(c, who) ?: return Said("No clear contact for ${m.who}. Say the full name", ok = false)
        val (done, detail) = PhoneActions.run(c, "hermes-${System.currentTimeMillis()}", "message", JSONObject().put("to", p.name).put("text", body).put("via", m.via ?: "auto"))
        return Said(if (done) detail.replaceFirstChar { it.uppercase() } else "Text ${p.name}: $detail")
    }

    /** "no, I meant X": the last command again with X, and the words he used remembered for next time. */
    private fun correct(c: Context, meant: String): Said {
        val redo = when (val l = last) {
            is Open -> { Book.learn(c, l.target, meant); Open(meant) }
            is Call -> CallWay.via(meant)?.let { l.copy(via = it) } ?: run { Book.learn(c, l.who, meant); l.copy(who = meant) }
            is Message -> when (meant.lowercase()) { "whatsapp", "on whatsapp" -> l.copy(via = "whatsapp"); "sms", "a text", "text" -> l.copy(via = "sms"); else -> { Book.learn(c, l.who, meant); l.copy(who = meant) } }
            is Navigate -> Navigate(meant)
            is Play -> Play(meant)
            else -> return Said("Nothing to correct. Say the whole command again", ok = false)
        }
        last = redo
        return run(c, redo)
    }

    /** Android lets an app switch only a few things itself; the rest open their own panel for his tap. */
    private fun toggle(c: Context, t: Toggle): Said = when (Commands.norm(t.target)) {
        "torch", "flashlight", "flash light" -> torch(c, t.on)
        "dnd", "do not disturb" -> run(c, Dnd(t.on))
        "wifi", "wi fi" -> start(c, Intent(Settings.Panel.ACTION_WIFI), "Wi-Fi: tap to turn it ${if (t.on) "on" else "off"}")
        "internet", "mobile data", "data" -> start(c, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), "Internet: tap to switch it")
        "bluetooth" -> start(c, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth: tap to turn it ${if (t.on) "on" else "off"}")
        "nfc" -> start(c, Intent(Settings.Panel.ACTION_NFC), "NFC: tap to switch it")
        "airplane mode", "aeroplane mode" -> start(c, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), "Airplane mode: tap to switch it")
        "location", "gps" -> start(c, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "Location: tap to switch it")
        "hotspot" -> start(c, Intent("android.settings.TETHER_SETTINGS"), "Hotspot: tap to switch it")
        "volume", "sound" -> volume(c, Volume(if (t.on) "unmute" else "mute"))
        else -> Said("Apollo can't switch ${t.target}", ok = false)
    }

    private fun Pair<Boolean, String>.said(ok: String) = if (first) Said(ok) else Said(second, ok = false)

    private fun minutes(s: Int) = when {
        s % 3600 == 0 -> "${s / 3600} h"
        s % 60 == 0 -> "${s / 60} min"
        else -> "$s s"
    }

    private fun torch(c: Context, on: Boolean): Said {
        val cm = c.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return Said("No torch on this phone", ok = false)
        cm.setTorchMode(id, on)
        return Said(if (on) "Torch on" else "Torch off", undo = { torch(it, !on) })
    }

    private fun volume(c: Context, v: Volume): Said {
        val a = c.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val before = a.getStreamVolume(s)
        val back: (Context) -> Unit = { it.getSystemService(AudioManager::class.java).setStreamVolume(s, before, 0) }
        when (v.action) {
            "set" -> a.setStreamVolume(s, (a.getStreamMaxVolume(s) * (v.percent ?: 50) / 100.0).toInt(), 0)
            "mute" -> a.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> a.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            "down" -> a.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            else -> a.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
        return Said(when (v.action) { "set" -> "Volume ${v.percent}%"; "mute" -> "Muted"; "unmute" -> "Unmuted"; "down" -> "Volume down"; else -> "Volume up" }, undo = back)
    }

    private fun media(c: Context, action: String): Said {
        val key = when (action) { "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS }
        val a = c.getSystemService(AudioManager::class.java)
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        val opposite = mapOf("pause" to "resume", "resume" to "pause")[action]
        return Said(action.replaceFirstChar { it.uppercase() }, undo = opposite?.let { o -> { ctx -> media(ctx, o) } })
    }

    /** The contact from his phone book, then call_person through his approvals (a tap, as for Athena). */
    private fun call(c: Context, said: String, via: String?): Said {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return Said("Apollo needs your contacts to call $said", ok = false, needs = Manifest.permission.READ_CONTACTS)
        val who = Book.get(c, said)
        val people = contacts(c, who)
        val name = Commands.pick(who, people.keys.toList()) ?: return Said(if (people.isEmpty()) "No contact called $said" else "Which $said? Say the full name", ok = false)
        val id = "hermes-${System.currentTimeMillis()}"
        val (done, detail) = PhoneActions.run(c, id, "call_person", JSONObject().put("name", name).put("number", people.getValue(name)).put("via", via ?: "auto"))
        return Said(if (done) detail.replaceFirstChar { it.uppercase() } else "Call $name: $detail", ok = true)
    }

    private fun contacts(c: Context, who: String): Map<String, String> {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(who))
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY)
        return c.contentResolver.query(uri, cols, null, null, "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC")?.use { cur ->
            buildMap { while (cur.moveToNext()) { val n = cur.getString(0) ?: continue; if (n !in this) put(n, cur.getString(1) ?: continue) } }
        }.orEmpty()
    }

    val PAGES = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS, "wi fi" to Settings.ACTION_WIFI_SETTINGS, "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS, "sound" to Settings.ACTION_SOUND_SETTINGS, "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "hotspot" to "android.settings.TETHER_SETTINGS", "settings" to Settings.ACTION_SETTINGS,
    )

    /** A settings page by name ("wifi", "bluetooth settings"); null when it isn't one. Also phone_do open_settings. */
    fun settings(name: String): Intent? = PAGES[Commands.norm(name).removeSuffix(" settings").ifEmpty { "settings" }]?.let { Intent(it) }

    /** A launcher app by its package or the closest label: (label, launch intent). Also phone_do open_app. */
    fun app(c: Context, target: String): Pair<String, Intent>? {
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associate { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val label = apps.entries.firstOrNull { it.value == target.trim() }?.key ?: Commands.pick(target, apps.keys.toList()) ?: return null
        return pm.getLaunchIntentForPackage(apps.getValue(label))?.let { label to it }
    }

    /** A settings page by name, else the launcher app whose label matches. */
    private fun open(c: Context, said: String): Said {
        val target = Book.get(c, said)
        settings(target)?.let { return start(c, it, "Opened ${target.removeSuffix(" settings")} settings") }
        val (label, launch) = app(c, target) ?: return Said("No app called $target", ok = false)
        return start(c, launch, "Opened $label")
    }

    private fun start(c: Context, i: Intent, done: String): Said =
        runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Said(done) }.getOrElse { Said("Couldn't: ${it.javaClass.simpleName}", ok = false) }
}

/** What "no, I meant ..." taught: the words he says to the app or contact he means. On the phone only. */
object Book {
    private fun prefs(c: Context) = c.getSharedPreferences("hermes_book", Context.MODE_PRIVATE)
    fun get(c: Context, said: String): String = prefs(c).getString(Commands.norm(said), null) ?: said
    fun learn(c: Context, said: String, meant: String) {
        if (Commands.norm(said) != Commands.norm(meant)) prefs(c).edit().putString(Commands.norm(said), meant.trim()).apply()
    }
}
