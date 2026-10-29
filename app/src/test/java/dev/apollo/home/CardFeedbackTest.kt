package dev.apollo.home

import org.junit.Assert.assertEquals
import org.junit.Test

class CardFeedbackTest {
    // Athena reads these words back from card_feedback facts; they are part of what she learns from.
    @Test fun theSwipeWordsAreFixed() {
        assertEquals("seen", CardFeedback.SEEN)
        assertEquals("less like this", CardFeedback.LESS)
    }
}

class CountdownTest {
    @org.junit.Test fun countdownWords() {
        val m = 60_000L
        org.junit.Assert.assertEquals("12m", Countdown.words(12 * m, 0))
        org.junit.Assert.assertEquals("3h 5m", Countdown.words(185 * m, 0))
        org.junit.Assert.assertEquals("2d 4h", Countdown.words((52 * 60 + 30) * m, 0))
        org.junit.Assert.assertEquals("now", Countdown.words(0, 5))
    }
}
