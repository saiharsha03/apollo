package dev.apollo.clio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClioShipTest {
    @Test fun calypsoAndKeysNeverLeave() {
        assertTrue(ClioShip.keep(ClioLine(1, "hermes", "info", "torch", "ran")))
        assertFalse(ClioShip.keep(ClioLine(1, "calypso", "info", "open", "")))
        assertFalse(ClioShip.keep(ClioLine(1, "net", "info", "sent", "opened Calypso")))
        assertFalse(ClioShip.keep(ClioLine(1, "net", "error", "auth", "sk-ant-abc")))
        assertFalse(ClioShip.keep(ClioLine(1, "net", "error", "auth", "x".repeat(10) + "A".repeat(40))))
    }
}
