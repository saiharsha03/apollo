package dev.apollo

import dev.apollo.approvals.Level
import dev.apollo.approvals.RuleRow
import dev.apollo.approvals.Rules
import dev.apollo.approvals.Seed
import dev.apollo.ossa.CallGate
import dev.apollo.ossa.OssaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Security scan fixes (2026-10-27) that are pure enough to test here. */
class SecfixTest {
    @Test fun anAliasIsCheckedUnderItsHandlersRow() {
        val aliases = mapOf("calendar_add" to "add_his_calendar")
        val rules = mapOf("add_his_calendar" to Level.NEVER)
        assertEquals(Level.NEVER, Rules.levelFor(rules, Rules.key(aliases, "calendar_add")))      // F8: not TAP by default
        assertEquals("open_url", Rules.key(aliases, "open_url"))
    }

    @Test fun aYesOnAQueuedAskFollowsHisRuleNow() {
        assertEquals(Level.TAP, Rules.atAnswer(Level.TAP, Level.TAP))
        assertEquals(Level.FINGERPRINT, Rules.atAnswer(Level.TAP, Level.FINGERPRINT))                   // F17: tightened since
        assertEquals(null, Rules.atAnswer(Level.TAP, Level.NEVER))
        assertEquals(Level.FINGERPRINT, Rules.atAnswer(Level.FINGERPRINT, Level.FREE))                  // never looser than asked
    }

    @Test fun theApprovalsScreenListsEveryAction() {
        val rows = listOf(RuleRow("lights", Level.FREE, "Owner", 1L))
        val shown = Rules.listed(rows, listOf("lights", "calendar_delete", "open_url"))
        assertEquals(listOf("calendar_delete", "lights", "open_url"), shown.map { it.action })
        assertEquals(Level.TAP, shown.first { it.action == "open_url" }.level)
    }

    @Test fun herCallsFollowHisCallRow() {
        assertEquals(CallGate.RING, OssaRules.gate(Level.FREE))
        assertEquals(CallGate.MESSAGE, OssaRules.gate(Level.TAP))
        assertEquals(CallGate.MESSAGE, OssaRules.gate(Level.FINGERPRINT))
        assertEquals(CallGate.REFUSE, OssaRules.gate(Level.NEVER))                                   // F7
    }

    @Test fun openingAnAppOrSettingsIsFree() {
        assertEquals(Level.FREE, Seed.APPROVALS.toMap()["open_app"])
        assertEquals(Level.FREE, Seed.APPROVALS.toMap()["open_settings"])
    }

    @Test fun onlyTheSystemsBluetoothBroadcastsCount() {
        assertEquals(true, dev.apollo.senses.CarLink.connected("android.bluetooth.device.action.ACL_CONNECTED"))
        assertEquals(false, dev.apollo.senses.CarLink.connected("android.bluetooth.device.action.ACL_DISCONNECTED"))
        assertEquals(null, dev.apollo.senses.CarLink.connected(null))                                // F12
        assertEquals(null, dev.apollo.senses.CarLink.connected("x.FAKE"))
    }

    @Test fun noExportedAssistDoor() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.intent.action.ASSIST"))                               // F5/F14
        assertFalse(manifest.contains("AssistEntryActivity"))
    }
}
