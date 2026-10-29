package dev.apollo

import dev.apollo.net.Sync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Final review round fixes that are pure enough to test here. */
class Fix3Test {
    @Test fun onlyTheSensesNumbersAreSettings() {
        for (n in listOf("night_start_h", "night_end_h_sat", "watch_idle_min", "reask_min", "answer_min", "still_idle_min"))
            assertTrue(n, Sync.SENSE_SETTINGS.matches(n))
        for (n in listOf("nav_order", "theme", "goal_steps", "sarvam_daily_cap", "night_start_h_xyz", "debug"))
            assertFalse(n, Sync.SENSE_SETTINGS.matches(n))
    }
}
