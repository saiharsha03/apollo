package dev.apollo.ossa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OssaRulesTest {
    // The 10-26 bug: the car connected 45 s into the call and the call stayed on the phone.
    @Test fun aCallMovesToBluetoothWhenItAppears() {
        assertEquals(Route.BLUETOOTH, OssaRules.moveTo(Route.EARPIECE, setOf(Route.EARPIECE, Route.SPEAKER, Route.BLUETOOTH)))
        assertNull(OssaRules.moveTo(Route.BLUETOOTH, setOf(Route.BLUETOOTH, Route.EARPIECE)))
        assertNull(OssaRules.moveTo(Route.EARPIECE, setOf(Route.EARPIECE, Route.SPEAKER)))
    }

    @Test fun onlyTalkedCallsStayCalls() {
        assertTrue(Ending.entries.filter { it.asMessage }.containsAll(listOf(Ending.DECLINED, Ending.MISSED, Ending.AS_MESSAGE)))
        assertTrue(!Ending.TALKED.asMessage)
        val line = OssaRules.messageLine(CallAsk("1", "Morning", "Leave by 8:40.", "athena"), Ending.MISSED)
        assertTrue(line.contains("missed") && line.contains("Leave by 8:40."))
    }
}

class OssaCodeTest {
    @org.junit.Test fun failureCodesAreSanitized() {
        org.junit.Assert.assertEquals("speech_7", OssaRules.code("speech_7"))
        org.junit.Assert.assertEquals("SecurityException__x_", OssaRules.code("SecurityException: x."))
    }
}
