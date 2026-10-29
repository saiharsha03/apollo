package dev.apollo.senses

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AllowTest {
    private val declared = listOf("android.permission.RECORD_AUDIO", "android.permission.READ_CALL_LOG", "android.permission.health.READ_RESTING_HEART_RATE")

    @Test fun stepKeysMapToTheirPromptOrScreen() {
        assertEquals(Allow.Perms(listOf("android.permission.READ_CALL_LOG")), Allow.way("call_log", declared))
        assertEquals(Allow.Perms(listOf("android.permission.RECORD_AUDIO")), Allow.way("perm_record_audio", declared))
        assertEquals(Allow.HealthAll, Allow.way("perm_read_resting_heart_rate", declared))
        assertEquals(Allow.Screen(Settings.ACTION_USAGE_ACCESS_SETTINGS), Allow.way("usage", declared))
        assertEquals(Allow.Car, Allow.way("car", declared))
    }

    @Test fun anythingElseIsRefused() {
        listOf("pair", "brain_key", "box_key", "iris", "perm_read_contacts", "perm_", "android.settings.WIFI_SETTINGS").forEach { assertNull(it, Allow.way(it, declared)) }
    }

    @Test fun onlyDawnsFullPageMayAsk() {
        assertEquals(Allow.HealthAll, Allow.forPage("dawn", true, "health", declared))
        assertNull(Allow.forPage("dawn", false, "health", declared))
        assertNull(Allow.forPage("weather", true, "health", declared))
        assertNull(Allow.forPage(null, true, "health", declared))
        assertNull(Allow.forPage("dawn", true, null, declared))
    }

    @Test fun onlyTheMainFramesTokenAsks() {
        org.junit.Assert.assertTrue(Allow.tokenOk("abc", "abc"))
        listOf(null, "", "abd").forEach { org.junit.Assert.assertFalse(Allow.tokenOk("abc", it)) }
        org.junit.Assert.assertFalse(Allow.tokenOk(null, "abc"))
        org.junit.Assert.assertFalse(Allow.tokenOk("", ""))
    }
}
