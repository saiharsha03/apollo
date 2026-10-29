package dev.apollo.ossa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTalkTest {
    private val ask = CallAsk("1", "Your 7 AM alarm", "Morning. You have a 9 o'clock.", "athena")

    @Test fun aTalkGoesRoundUntilSheHangsUp() {
        val t = CallTalk()
        assertEquals(CallTalk.Phase.LISTENING, t.spoke())
        assertNull(t.heard("what's the weather"))
        assertEquals(CallTalk.Phase.WAITING, t.phase)
        assertEquals("Cold, 4 degrees.", t.answered("Cold, 4 degrees."))
        assertEquals(CallTalk.Phase.LISTENING, t.spoke())
        assertNull(t.heard("thanks, bye"))
        assertEquals("Bye, Owner.", t.answered("Bye, Owner. [hang up]"))
        assertEquals(CallTalk.Phase.ENDED, t.spoke())
    }

    @Test fun silenceTwiceOrNoAnswerEndsIt() {
        val t = CallTalk()
        t.spoke()
        assertNull(t.heard(""))
        assertEquals(CallTalk.Phase.LISTENING, t.phase)
        assertEquals(CallTalk.QUIET_BYE, t.heard(" "))
        assertEquals(CallTalk.Phase.ENDED, t.spoke())

        val u = CallTalk()
        u.spoke()
        u.heard("hi")
        assertEquals(CallTalk.NO_ANSWER, u.answered(null))
        assertEquals(CallTalk.Phase.ENDED, u.spoke())

        // No recognizer on the phone: goodbye, then the end.
        val v = CallTalk()
        v.spoke()
        assertEquals(CallTalk.CANT_HEAR, v.cantHear())
        assertEquals(CallTalk.Phase.ENDED, v.spoke())
    }

    @Test fun words() {
        assertEquals("ok" to true, CallTalk.split("ok [Hang Up]"))
        assertEquals("ok" to false, CallTalk.split("ok"))
        assertTrue(CallTalk.line(ask, " hi ", first = true).startsWith("[on the call: you called him (Your 7 AM alarm) and opened with: Morning."))
        assertEquals("[on the call] hi", CallTalk.line(ask, "hi", first = false))
    }
}
