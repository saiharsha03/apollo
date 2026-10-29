package dev.apollo.chat

import dev.apollo.net.Reaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatTextTest {
    private val now = java.time.ZonedDateTime.of(2026, 10, 26, 15, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    @Test fun daySeparatorsSayTodayYesterdayThenTheDate() {
        assertEquals("Today", Chat.day(now - hour, now))
        assertEquals("Yesterday", Chat.day(now - 20 * hour, now))
        assertEquals("Fri Oct 23", Chat.day(now - 3 * 24 * hour, now))
        assertEquals("Sat Oct 25, 2025", Chat.day(now - 366 * 24 * hour, now))
        assertEquals(listOf("Yesterday", null, "Today", null), Chat.dayBreaks(listOf(now - 20 * hour, now - 19 * hour, now - hour, now), now))
    }

    @Test fun aPhotoStartsAThreadNamedForTheWordsOrTheTimeNeverTheFile() {
        assertEquals("Look at this", Chat.title("Look at this\nsecond line", image = true, now = now))
        assertEquals("Photo 3:00 PM", Chat.title("  ", image = true, now = now))
        assertEquals("File 3:00 PM", Chat.title("", image = false, now = now))
    }

    @Test fun onlyConversationsAreNotSystemThreads() {
        assertEquals(listOf(false, false, true, true, true, true, true),
            listOf("chat", "zeus", "facts", "routines", "bank", "calls", "taps").map { dev.apollo.net.ChatThread("1", "t", 0, "", kind = it).system })
    }

    @Test fun markdownBlocks() {
        val b = Markdown.parse("# Plan\nFirst line\nsecond line\n\n- milk\n2. eggs\n```\nval x = 1\n```")
        assertEquals(Markdown.Heading(listOf(Markdown.Span("Plan"))), b[0])
        assertEquals(Markdown.Para(listOf(Markdown.Span("First line\nsecond line"))), b[1])
        assertEquals(Markdown.Item("•", listOf(Markdown.Span("milk"))), b[2])
        assertEquals(Markdown.Item("2.", listOf(Markdown.Span("eggs"))), b[3])
        assertEquals(Markdown.Code("val x = 1"), b[4])
    }

    @Test fun markdownInlineMarks() {
        val s = Markdown.inline("**Due** *today* at `9:00`, see [the page](https://x.dev/a) or https://y.dev/b.")
        assertEquals(Markdown.Span("Due", bold = true), s[0])
        assertEquals(Markdown.Span("today", italic = true), s[2])
        assertEquals(Markdown.Span("9:00", code = true), s[4])
        assertEquals(Markdown.Span("the page", url = "https://x.dev/a"), s[6])
        assertEquals(Markdown.Span("https://y.dev/b", url = "https://y.dev/b"), s[8])
        assertEquals(".", s.last().text)
    }

    @Test fun snakeCaseAndLoneStarsStayAsTyped() {
        assertEquals(listOf(Markdown.Span("use my_app_name and 2 * 3")), Markdown.inline("use my_app_name and 2 * 3"))
        assertEquals(listOf(Markdown.Span("**open")), Markdown.inline("**open"))
    }

    @Test fun reactionsAreOnePerPersonAndEmoji() {
        val a = Reactions.apply(emptyList(), "owner", "👍", true)
        val b = Reactions.apply(Reactions.apply(a, "owner", "👍", true), "iris", "👍", true)
        assertEquals(2, b.size)
        assertEquals(listOf(Reactions.Chip("👍", 2, true)), Reactions.chips(b))
        assertEquals(listOf(Reaction("iris", "👍")), Reactions.apply(b, "owner", "👍", false))
    }

    @Test fun messengerOutboxKindsGoToChat() =
        listOf("reaction", "edit", "delete", "typing").forEach { assertEquals(it, dev.apollo.net.Route.CHAT, dev.apollo.net.Outbox.route(it)) }

    @Test fun typingEndsWhenTheyAnswerOrTimeOut() {
        var t = Typing.set(emptyMap(), "7", "athena", true, 0)
        t = Typing.set(t, "7", "iris", true, 0)
        assertEquals("Athena and Iris are typing", Typing.line(Typing.who(t, "7", 1).map { it.replaceFirstChar(Char::uppercase) }))
        assertEquals(listOf("iris"), Typing.who(Typing.set(t, "7", "athena", false, 0), "7", 1))
        assertNull(Typing.line(Typing.who(t, "7", ChatNow.TYPING_MS + 1)))
    }
}
