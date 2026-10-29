package dev.apollo.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadPagesTest {
    @Test fun theNextPageIsBeforeTheOldestServerMessage() {
        assertEquals("98", ThreadPages.before(listOf("120", "98", "local-1", "301")))
        assertNull(ThreadPages.before(emptyList()))
    }

    @Test fun pagingFollowsTheFetchedPagesNotTheOldestCachedOne() {
        // Cached 1-200 from before, then the newest page 401-500: the next page is before 401, so 201-400 still come.
        val newest = (401..500).map { it.toString() }
        assertEquals("401", ThreadPages.edge(newest, null))
        assertEquals("301", ThreadPages.edge((301..400).map { it.toString() }, "401"))
        assertEquals("301", ThreadPages.edge(emptyList(), "301"))
    }

    @Test fun aShortPageMeansTheStart() {
        assertTrue(ThreadPages.more(ThreadPages.SIZE))
        assertFalse(ThreadPages.more(ThreadPages.SIZE - 1))
    }
}
