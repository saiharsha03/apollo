package dev.apollo.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SarvamVoiceTest {
    @Test fun wavHeaderWrapsThePcm() {
        val w = SarvamVoice.wav(ByteArray(320))
        assertEquals(364, w.size)
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals("data", String(w, 36, 4))
    }

    @Test fun quietEndsAReplyAfterHeSpoke() {
        val q = Quiet()
        assertFalse(q.done(true, 100))
        repeat(14) { assertFalse(q.done(false, 100)) }
        assertTrue(q.done(false, 100))
        val none = Quiet()
        repeat(79) { assertFalse(none.done(false, 100)) }
        assertTrue(none.done(false, 100))
        assertFalse(none.heard)
    }

    @Test fun loudnessFromPcm() {
        assertFalse(Quiet.loud(ByteArray(200), 200))
        val loud = ByteArray(200) { i -> if (i % 2 == 0) 0 else 0x20 }
        assertTrue(Quiet.loud(loud, 200))
    }
}
