package dev.apollo.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTapTest {
    @Test fun theHomeTileTapIsCarriedOut() = assertEquals("light-1" to false, AppWeb.homeTap("home", "home", "light-1", false))

    @Test fun aTileTapIsCheckedAgainstHisRuleForThatKindOfDevice() {
        assertEquals("lights", AppWeb.homeAction(dev.apollo.house.Kind.LIGHT))
        assertEquals("plug", AppWeb.homeAction(dev.apollo.house.Kind.SWITCH))
        assertEquals("home_tap", AppWeb.homeAction(null))
    }

    @Test fun otherTapsOnlyBecomeFacts() {
        assertNull(AppWeb.homeTap("weather", "home", "light-1", true))
        assertNull(AppWeb.homeTap("home", "refresh", "light-1", true))
        assertNull(AppWeb.homeTap("home", "home", "", true))
        assertNull(AppWeb.homeTap("home", "home", "light-1", null))
    }

    @Test fun aHomeWidgetStaysOnItsOwnApp() {
        assertTrue(AppWeb.stays("weather", "weather"))
        assertFalse(AppWeb.stays("weather", "money"))
        assertFalse(AppWeb.stays("weather", null))
        assertTrue(AppWeb.stays(null, "money"))     // a full page isn't pinned
    }
}
