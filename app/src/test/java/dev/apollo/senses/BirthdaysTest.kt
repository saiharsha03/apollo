package dev.apollo.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BirthdaysTest {
    // Calypso keeps sealed people as personKey hashes: a sealed person's birthday is caught by name, any case.
    @Test fun aSealedPersonsBirthdayStaysOnThePhone() {
        val sealed = setOf(NoticeRules.personKey("Pat Moore"))
        org.junit.Assert.assertTrue(Birthdays.sealedPerson("pat moore", sealed))
        org.junit.Assert.assertFalse(Birthdays.sealedPerson("Sam", sealed))
    }

    @Test fun datesFromContactsAndCalendar() {
        assertEquals("1990-04-12", Birthdays.date("1990-04-12"))
        assertEquals("04-12", Birthdays.date("--04-12"))
        assertEquals("04-12", Birthdays.date("0000-04-12"))
        assertEquals("1990-04-12", Birthdays.date("19900412"))
        assertNull(Birthdays.date("April 12"))
        assertNull(Birthdays.date(null))
    }

    @Test fun namesFromBirthdayEvents() {
        assertEquals("Sam Lee", Birthdays.nameOf("Sam Lee's birthday"))
        assertEquals("Mom", Birthdays.nameOf("Mom’s Birthday"))
        assertNull(Birthdays.nameOf("Team lunch"))
    }
}
