package dev.apollo.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapTilesTest {
    @Test fun aPageAsksForRealTilesOnly() {
        assertEquals(Triple(12, 1130, 1513), MapTiles.parse("/tile/12/1130/1513.png"))
        assertEquals(Triple(12, 1130, 1513), MapTiles.parse("/tile/dark/12/1130/1513"))     // Zeus's zmap
        assertEquals(Triple(0, 0, 0), MapTiles.parse("/tile/0/0/0.png"))
        assertNull(MapTiles.parse("/tile/2/4/0.png"))          // off the map at that zoom
        assertNull(MapTiles.parse("/tile/21/0/0.png"))
        assertNull(MapTiles.parse("/tile/3/1/1.png/../x"))
        assertNull(MapTiles.parse("/tile/x/1/1.png"))
    }
}
