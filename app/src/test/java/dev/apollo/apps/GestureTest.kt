package dev.apollo.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTest {
    @Test fun aPageCallCountsAsHisOnlyRightAfterATouch() {
        assertFalse(AppWeb.gesture(0, 500))             // never touched
        assertTrue(AppWeb.gesture(10_000, 10_300))
        assertTrue(AppWeb.gesture(10_000, 11_000))
        assertFalse(AppWeb.gesture(10_000, 11_001))     // a script long after the touch
        assertFalse(AppWeb.gesture(10_000, 9_000))
    }

    @Test fun onlyAClickCountsNotAScrollOrLongPress() {
        assertTrue(AppWeb.click(1_000, 1_120, 3f, 2f, 16f))
        assertFalse(AppWeb.click(1_000, 1_120, 0f, 40f, 16f))     // a scroll
        assertFalse(AppWeb.click(1_000, 1_900, 0f, 0f, 16f))      // a long press
    }
}
