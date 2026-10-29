package dev.apollo.net

import org.junit.Assert.assertEquals
import org.junit.Test

class FactsTest {
    private fun f(id: Long, at: Long, urgent: Boolean = false) = QueuedFact(id, "k", at, "{}", urgent)

    @Test fun urgentFactsGoFirstThenOldest() {
        val q = listOf(f(1, 300), f(2, 100), f(3, 500, urgent = true), f(4, 200))
        assertEquals(listOf(3L, 2L, 4L, 1L), Facts.nextBatch(q).map { it.id })
    }

    @Test fun aBatchIsAtMostMax() {
        val q = (1L..450L).map { f(it, it) }
        assertEquals(200, Facts.nextBatch(q).size)
        assertEquals(1L, Facts.nextBatch(q).first().id)
    }

    @Test fun anEmptyQueueSendsNothing() = assertEquals(emptyList<QueuedFact>(), Facts.nextBatch(emptyList()))

    // A restore can roll facts.db's row ids back; a fact's own random id is what the server dedupes on.
    @Test fun aFactsWireIdIsItsOwnCid() {
        assertEquals("c-1", Facts.wireId(QueuedFact(7, "k", 1, "{}", false, "c-1"), "inst"))
        assertEquals("inst:7", Facts.wireId(f(7, 1), "inst"))
    }

    @Test fun theFactsQueueIsNeverBackedUp() {
        assertEquals(true, dev.apollo.mnemosyne.Mnemosyne.SKIP_DB.matches("facts.db"))
        assertEquals(true, dev.apollo.mnemosyne.Mnemosyne.SKIP_PREFS.matches("facts.xml"))
        assertEquals(false, dev.apollo.mnemosyne.Mnemosyne.SKIP_DB.matches("apollo.db"))
    }
}
