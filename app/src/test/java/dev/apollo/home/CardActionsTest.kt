package dev.apollo.home

import dev.apollo.net.CardButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardActionsTest {
    @Test fun eachKindBecomesItsPhoneAction() {
        assertEquals(CardActions.Phone("call_person", mapOf("name" to "Amma")), CardActions.parse("call:Amma"))
        assertEquals(CardActions.Phone("alarm", mapOf("hour" to 6, "minute" to 30)), CardActions.parse("alarm: 6:30"))
        assertEquals(CardActions.Phone("open_url", mapOf("url" to "geo:0,0?q=Tim%20Hortons%20%26%20Co")), CardActions.parse("maps:Tim Hortons & Co"))
        assertEquals(CardActions.Phone("open_app", mapOf("app" to "Spotify")), CardActions.parse("open_app:Spotify"))
        assertEquals(CardActions.OpenMini("trips"), CardActions.parse("Open:Trips"))
    }

    @Test fun badOnesGoToAthenaAsLabels() {
        for (bad in listOf(null, "", "call:", "alarm:25:00", "alarm:7pm", "lock:door", "no colon")) assertNull(bad, CardActions.parse(bad))
    }

    @Test fun removeHasItsOwnRoute() = assertEquals(dev.apollo.net.Route.CARD_REMOVE, dev.apollo.net.Outbox.route("card_remove"))

    @Test fun buttonsKeepTheirRunThroughStorage() {
        val b = CardButton("Call Amma", "call:Amma")
        assertEquals(b, CardButton.of(b.stored))
        assertEquals(CardButton("Seen it"), CardButton.of(CardButton("Seen it").stored))
    }
}
