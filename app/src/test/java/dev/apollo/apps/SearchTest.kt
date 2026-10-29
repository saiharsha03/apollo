package dev.apollo.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private val money = MiniApp("money", "Money", "", "Spending and bills", true, listOf("pages/index.html", "pages/week-view.html"), emptyList(), null)
    private val all = Search.apps(listOf(money)) { "pages/index.html" } + listOf(Hit("Senses", "What Apollo can see, and your car", "senses"), Hit("Gym", "A saved place", "geo:42.4,-71.0"))

    @Test fun everyWordMustMatchAndNamesFirst() {
        assertEquals(listOf("money", "money/pages/week-view.html").map { "app:$it" }, Search.find("mon", all).map { it.go })
        assertEquals(listOf("app:money/pages/week-view.html"), Search.find("money week", all).map { it.go })
        assertEquals(listOf("senses"), Search.find("CAR", all).map { it.go })
        assertEquals(emptyList<Hit>(), Search.find("  ", all))
    }

    @Test fun pageWordsAreReadable() = assertEquals("week view", Search.pageWord("pages/week-view.html"))
}
