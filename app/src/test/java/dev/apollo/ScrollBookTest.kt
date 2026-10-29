package dev.apollo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollBookTest {
    private val more = Spot(Place.MORE)
    private val lemnos = Spot(Place.MORE, "lemnos")
    private val chat = Spot(Place.CHAT)

    @Test fun aSpotBehindHimKeepsItsPlace() {
        val book = ScrollBook()
        // More -> Lemnos: More is on the stack when it leaves.
        book.live(BackStack.push(emptyList(), more, lemnos) + lemnos)
        book.leave(more.wire, "more", Mark(840))
        book.leave(chat.wire, "threads", Mark(3, 12))
        assertEquals(Mark(840), book.get(more.wire, "more"))
        assertNull(book.get(chat.wire, "threads"))
    }

    @Test fun poppedOrStartedOverLosesIt() {
        val book = ScrollBook()
        book.live(listOf(more, lemnos))
        book.leave(more.wire, "more", Mark(840))
        book.leave(lemnos.wire, "files", Mark(5, 2))
        // Back from Lemnos to More: Lemnos is gone, More keeps its place until it shows again.
        book.live(listOf(more))
        assertNull(book.get(lemnos.wire, "files"))
        assertEquals(Mark(840), book.get(more.wire, "more"))
        book.leave(lemnos.wire, "files", Mark(9))
        assertNull(book.get(lemnos.wire, "files"))
        // A nav tab starts over.
        book.live(listOf(chat))
        assertNull(book.get(more.wire, "more"))
    }

    @Test fun aPageKeepsItsUrl() {
        val book = ScrollBook()
        val app = Spot(Place.HOME, "app:money")
        book.live(listOf(app))
        book.leave(app.wire, "web:money", Mark(1200, url = "https://apollo.local/money/pages/week.html"))
        assertEquals("https://apollo.local/money/pages/week.html", book.get(app.wire, "web:money")?.url)
    }
}
