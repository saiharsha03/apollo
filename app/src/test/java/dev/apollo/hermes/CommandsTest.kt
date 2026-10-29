package dev.apollo.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {
    private fun p(s: String) = Commands.parse(s)

    @Test fun commands() {
        assertEquals(Torch(true), p("torch on"))
        assertEquals(Torch(false), p("Turn off the flashlight."))
        assertEquals(Open("Spotify"), p("open Spotify"))
        assertEquals(Open("wifi settings"), p("open wifi settings"))
        assertEquals(Alarm(6, 0), p("wake me at 6"))
        assertEquals(Alarm(18, 30), p("set an alarm for 6:30 pm"))
        assertEquals(Timer(600), p("timer 10 min"))
        assertEquals(Timer(5400), p("set a timer for 1 hour 30 minutes"))
        assertEquals(Volume("set", 30), p("volume 30"))
        assertEquals(Volume("down"), p("volume down"))
        assertEquals(Volume("mute"), p("mute"))
        assertEquals(Call("mom"), p("call mom"))
        assertEquals(Media("next"), p("skip"))
        assertEquals(Navigate("Kohl's"), p("take me to Kohl's"))
        assertEquals(Torch(true), p("Hey Athena, torch on"))
    }

    @Test fun everythingElseGoesToAthena() {
        assertNull(p("call me at 6"))
        assertNull(p("remind me to call mom"))
        assertNull(p("what's the weather like"))
        assertNull(p(""))
        assertNull(p("set an alarm"))
    }

    @Test fun theCommandsZeusHad() {
        assertEquals(Remind("call mom", seconds = 1200), p("remind me to call mom in 20 min"))
        assertEquals(Remind("take the bins out", hour = 18, minute = 0), p("remind me at 6 pm to take the bins out"))
        assertEquals(Remind("pay rent", hour = 9, minute = 0, tomorrow = true), p("remind me tomorrow at 9 to pay rent"))
        assertEquals(Play("Blinding Lights"), p("play Blinding Lights on Spotify"))
        assertEquals(Media("resume"), p("play music"))
        assertEquals(Agenda("tomorrow"), p("what's on tomorrow"))
        assertEquals(Agenda("friday"), p("am I free friday"))
        assertNull(p("what's on netflix"))
        assertEquals(Dnd(true), p("dnd on"))
        assertEquals(Dnd(false), p("turn off do not disturb"))
        assertEquals(Message("mom", "running late"), p("text mom running late"))
        assertEquals(Message("Amma", "call me", "whatsapp"), p("whatsapp Amma saying call me"))
        assertEquals(Message("dad", null), p("text dad"))
        assertEquals(Call("Amma", "whatsapp_video"), p("video call Amma"))
        assertEquals(Call("Sam", "whatsapp_voice"), p("call Sam on WhatsApp"))
        assertEquals(Correction("Spotify Lite"), p("no, I meant Spotify Lite"))
        assertEquals(Toggle("wifi", true), p("turn on wifi"))
        assertEquals(Toggle("bluetooth", false), p("switch bluetooth off"))
    }

    @Test fun clockAndDuration() {
        assertEquals(12 to 0, Commands.clock("noon"))
        assertEquals(0 to 15, Commands.clock("12:15 am"))
        assertNull(Commands.clock("13 pm"))
        assertEquals(1800, Commands.duration("half an hour"))
        assertNull(Commands.duration("no time here"))
    }

    @Test fun picksTheAppHeMeant() {
        val apps = listOf("Spotify", "WhatsApp", "WhatsApp Business", "Microsoft Teams", "Settings")
        assertEquals("Spotify", Commands.pick("spotfy", apps))
        assertEquals("WhatsApp", Commands.pick("whatsapp", apps))
        assertEquals("Microsoft Teams", Commands.pick("teams", apps))
        assertNull(Commands.pick("zzz", apps))
        assertEquals("Mom", Commands.pick("mom", listOf("Mom", "Mom Work")))
        assertNull(Commands.pick("anna", listOf("Anna Lee", "Anna Kim")))
    }
}
