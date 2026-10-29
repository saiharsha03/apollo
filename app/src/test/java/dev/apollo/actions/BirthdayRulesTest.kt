package dev.apollo.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BirthdayRulesTest {
    @Test fun datesAreContactsOwnShapes() {
        assertEquals("--10-25", BirthdayRules.startDate("10-25"))
        assertEquals("--02-29", BirthdayRules.startDate("02-29"))
        assertEquals("1990-10-25", BirthdayRules.startDate("1990-10-25"))
        assertNull(BirthdayRules.startDate("2026-02-29"))
        assertNull(BirthdayRules.startDate("13-01"))
        assertNull(BirthdayRules.startDate("Oct 25"))
    }

    @Test fun theSameNameFindsHisContactElseANewOne() {
        val his = listOf(1L to "Sam Lee", 2L to "Taylor")
        assertEquals(1L, BirthdayRules.pick(his, " sam  lee"))
        assertNull(BirthdayRules.pick(his, "Sam"))
    }

    @Test fun undoDeletesAContactItMadeOnlyWhileItIsUnchanged() {
        val name = "vnd.android.cursor.item/name" to "Taylor"
        val bday = "vnd.android.cursor.item/contact_event" to "--10-25"
        val group = "vnd.android.cursor.item/group_membership" to "6"
        assertTrue(BirthdayRules.untouched(listOf(name, bday, group), "taylor", "--10-25"))
        assertFalse(BirthdayRules.untouched(listOf(name, bday, "vnd.android.cursor.item/phone_v2" to "+1555"), "Taylor", "--10-25"))
        assertFalse(BirthdayRules.untouched(listOf("vnd.android.cursor.item/name" to "Taylor K", bday), "Taylor", "--10-25"))
        assertFalse(BirthdayRules.untouched(listOf(name, "vnd.android.cursor.item/contact_event" to "--10-26"), "Taylor", "--10-25"))
        assertFalse(BirthdayRules.untouched(emptyList(), "Taylor", "--10-25"))
    }
}
