package dev.apollo.actions

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.apollo.R
import dev.apollo.clio.Clio
import dev.apollo.net.Facts
import org.json.JSONObject

/**
 * Quick Settings tiles and launcher shortcuts. "Ask Athena" is fixed (opens the Ask sheet listening). Three more are
 * Athena's (phone_do `tile`): she sets each one's label, second line and where a tap goes (as notify's open, a call
 * included: his tap is the yes, and the tile shows whom it calls). Shortcuts (phone_do `shortcut`) never call: they live on.
 */
object Tiles {
    const val SLOTS = 3
    private fun prefs(c: Context) = c.getSharedPreferences("athena_tiles", Context.MODE_PRIVATE)
    private val SLOT_CLASSES = listOf(AthenaTile1::class.java, AthenaTile2::class.java, AthenaTile3::class.java)

    fun spec(c: Context, slot: Int): JSONObject? = prefs(c).getString("$slot", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun set(c: Context, a: JSONObject): Pair<Boolean, String> {
        val slot = a.optInt("slot")
        if (slot !in 1..SLOTS) return false to "slot is 1 to $SLOTS"
        val label = a.optString("label").trim().take(20)
        val before = prefs(c).getString("$slot", null)
        if (label.isEmpty()) prefs(c).edit().remove("$slot").apply()
        else prefs(c).edit().putString("$slot", JSONObject().put("label", label).put("sub", a.optString("sub").trim().take(30)).put("open", a.optString("open").trim().take(500)).toString()).apply()
        refresh(c, slot)
        Undo.stage { ctx -> prefs(ctx).edit().apply { if (before == null) remove("$slot") else putString("$slot", before) }.apply(); refresh(ctx, slot) }
        return true to if (label.isEmpty()) "tile $slot cleared" else "tile $slot is \"$label\" (he adds it once to his quick settings)"
    }

    private fun refresh(c: Context, slot: Int) = runCatching { TileService.requestListeningState(c, ComponentName(c, SLOT_CLASSES[slot - 1])) }

    /** What the tile shows: her label, and for a call Apollo's own words, so a tap does only what it says. */
    fun show(s: TileService, slot: Int) {
        val t = s.qsTile ?: return
        val spec = spec(s, slot)
        t.label = spec?.optString("label") ?: "Athena $slot"
        t.subtitle = spec?.let { Open.words(it.optString("open")) ?: it.optString("sub").ifEmpty { null } }
        t.state = if (spec == null) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    fun tap(s: TileService, slot: Int) {
        val spec = spec(s, slot) ?: return
        // She changed it while quick settings was open: show the new one, do nothing.
        if (s.qsTile?.label?.toString() != spec.optString("label")) return show(s, slot)
        Clio.info("tiles", "he tapped a tile", "$slot")
        Facts.post(s, "tile", JSONObject().put("slot", slot).put("label", spec.optString("label")))
        collapse(s, 7900 + slot, Open.intent(s, spec.optString("open")))
    }

    @Suppress("DEPRECATION")
    fun collapse(s: TileService, code: Int, i: android.content.Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 34) s.startActivityAndCollapse(PendingIntent.getActivity(s, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else s.startActivityAndCollapse(i)
    }

    private val ID = Regex("[a-z][a-z0-9-]{1,39}")

    fun shortcut(c: Context, a: JSONObject): Pair<Boolean, String> {
        val sm = c.getSystemService(ShortcutManager::class.java) ?: return false to "no launcher shortcuts on this phone"
        val id = a.optString("id").trim()
        if (!ID.matches(id)) return false to "id is lower case letters, digits and dashes"
        return when (a.optString("op", "set")) {
            "remove" -> { runCatching { sm.disableShortcuts(listOf(id), "Athena removed this shortcut") }; sm.removeDynamicShortcuts(listOf(id)); true to "shortcut $id removed" }
            "pin" -> {
                val s = sm.dynamicShortcuts.firstOrNull { it.id == id } ?: return false to "set $id first"
                if (!sm.isRequestPinShortcutSupported) return false to "his launcher doesn't take pinned shortcuts"
                sm.requestPinShortcut(s, null); true to "asked him to pin $id (Android shows its own confirm)"
            }
            else -> {
                val label = a.optString("label").trim().take(25).ifEmpty { return false to "label is empty" }
                val open = a.optString("open").trim()
                if (Open.call(open) != null) return false to "a shortcut can't place a call; use a tile"
                val s = ShortcutInfo.Builder(c, id).setShortLabel(label).setIcon(Icon.createWithResource(c, R.mipmap.ic_launcher))
                    .setIntent(Open.intent(c, open, allowCall = false).setAction(android.content.Intent.ACTION_VIEW)).build()
                val max = sm.maxShortcutCountPerActivity
                sm.dynamicShortcuts.filter { it.id != id }.takeIf { it.size >= max }?.let { all -> sm.removeDynamicShortcuts(all.take(all.size - max + 1).map { it.id }) }
                runCatching { sm.enableShortcuts(listOf(id)) }
                sm.addDynamicShortcuts(listOf(s))
                Undo.stage { ctx -> ctx.getSystemService(ShortcutManager::class.java)?.removeDynamicShortcuts(listOf(id)) }
                true to "shortcut $id is \"$label\" (long-press Apollo's icon)"
            }
        }
    }
}

abstract class AthenaTile(private val slot: Int) : TileService() {
    override fun onStartListening() = Tiles.show(this, slot)
    // A secure lock screen asks for the unlock first: whoever holds the phone can't use her tiles.
    override fun onClick() = if (isSecure) unlockAndRun { Tiles.tap(this, slot) } else Tiles.tap(this, slot)
}
class AthenaTile1 : AthenaTile(1)
class AthenaTile2 : AthenaTile(2)
class AthenaTile3 : AthenaTile(3)

/** "Ask Athena": the Ask sheet, listening. Unlock first on a secure lock screen (her answers can carry his data). */
class AskTile : TileService() {
    override fun onClick() = if (isSecure) unlockAndRun { open() } else open()
    private fun open() = Tiles.collapse(this, 7900, dev.apollo.ask.AskActivity.intent(this, true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
