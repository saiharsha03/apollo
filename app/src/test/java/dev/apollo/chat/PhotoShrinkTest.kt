package dev.apollo.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoShrinkTest {
    @Test fun theLongEdgeGoesTo1568AndTheShapeStays() {
        assertEquals(1568 to 1176, PhotoShrink.target(4000, 3000))
        assertEquals(882 to 1568, PhotoShrink.target(2268, 4032))       // portrait (Fold main camera, 16:9)
        assertEquals(1200 to 900, PhotoShrink.target(1200, 900))        // small already: never made bigger
        assertEquals(1568 to 1, PhotoShrink.target(20000, 3))           // a sliver keeps a pixel
    }

    @Test fun decodeStepNeverDropsBelowTheTarget() {
        assertEquals(2, PhotoShrink.sample(4000, 3000))                 // 2000 px decoded, then scaled to 1568
        assertEquals(4, PhotoShrink.sample(9248, 6936))                 // 2312 px
        assertEquals(1, PhotoShrink.sample(3000, 2000))                 // half would be 1500 < 1568
        assertEquals(1, PhotoShrink.sample(800, 600))
    }

    @Test fun turnsAndNames() {
        assertEquals(90, PhotoShrink.degrees(6))                        // EXIF ORIENTATION_ROTATE_90
        assertEquals(0, PhotoShrink.degrees(1))
        assertEquals("IMG_1.jpg", PhotoShrink.jpgName("IMG_1.HEIC"))
        assertEquals("shot.jpg", PhotoShrink.jpgName("shot"))
    }
}
