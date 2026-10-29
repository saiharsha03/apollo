package dev.apollo.house

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseRulesTest {
    private val home = Snapshot(
        Link.Connected, listOf(Room("r1", "Bedroom"), Room("r2", "Garage")),
        listOf(
            Device("l1", "Bed lamp", Kind.LIGHT, "r1", traits = setOf(Trait.ON_OFF, Trait.LEVEL)),
            Device("l2", "Ceiling", Kind.LIGHT, "r1", traits = setOf(Trait.ON_OFF)),
            Device("f1", "Bed fan", Kind.FAN, "r1", traits = setOf(Trait.ON_OFF)),
            Device("g1", "Garage door", Kind.GARAGE, "r2", traits = setOf(Trait.ON_OFF)),
            Device("k1", "Front door", Kind.LOCK, "r2", traits = setOf(Trait.ON_OFF)),
        ),
    )

    @Test fun locksAndGaragesAreNeverControllable() {
        assertFalse(HouseRules.allowed(home.devices.first { it.id == "g1" }))
        assertFalse(HouseRules.allowed(home.devices.first { it.id == "k1" }))
        assertFalse(HouseRules.allowed(null))
        assertTrue(HouseRules.targets(home, "Garage door", Kind.entries.toSet()).isEmpty())
        assertTrue(HouseRules.targets(home, "Garage", Kind.entries.toSet()).isEmpty())
    }

    @Test fun aRoomNamesItsLightsOnly() {
        assertEquals(listOf("l1", "l2"), HouseRules.targets(home, "bedroom", setOf(Kind.LIGHT)).map { it.id })
        assertEquals(listOf("f1"), HouseRules.targets(home, "Bed fan", setOf(Kind.FAN)).map { it.id })
    }

    @Test fun aTilesDeviceIdPicksThatOneLampNotEveryLampWithItsName() {
        val two = home.copy(devices = home.devices + Device("l3", "Bed lamp", Kind.LIGHT, "r2", traits = setOf(Trait.ON_OFF)))
        assertEquals(listOf("l1", "l3"), HouseRules.targets(two, "Bed lamp", setOf(Kind.LIGHT)).map { it.id })
        assertEquals(listOf("l3"), HouseRules.targets(two, "Bed lamp", setOf(Kind.LIGHT), "l3").map { it.id })
        assertTrue(HouseRules.targets(two, "Garage door", Kind.entries.toSet(), "g1").isEmpty())      // still never a garage
    }

    @Test fun offIsJustPower() {
        assertEquals(listOf(Command.Power(false)), HouseRules.commands(false, 40, 2700, null, null))
        assertEquals(listOf(Command.Power(true), Command.Brightness(100), Command.ColorTemp(2700)), HouseRules.commands(true, 140, 2700, 10, null))
    }

    @Test fun matterUnitsRoundTrip() {
        assertEquals(254, HouseRules.toLevel(100))
        assertEquals(50, HouseRules.toPercent(HouseRules.toLevel(50)))
        assertEquals(2700, HouseRules.toKelvin(HouseRules.toMireds(2700)))
    }
}
