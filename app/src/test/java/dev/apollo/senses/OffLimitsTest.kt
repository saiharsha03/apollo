package dev.apollo.senses

import org.junit.Assert.assertEquals
import org.junit.Test

class OffLimitsTest {
    @Test fun legalAndSocialAreFound() {
        assertEquals(setOf("legal"), OffLimits.topics("Your court date with the lawyer"))
        assertEquals(setOf("social"), OffLimits.topics("New group invite from the club"))
        assertEquals(emptySet<String>(), OffLimits.topics("Dinner at 7? Bring the lawyers... no wait"))
    }

    @Test fun theMarkerNamesTopicsOnly() = assertEquals("(words withheld: legal, social)", OffLimits.withheld(setOf("legal", "social")))
}
