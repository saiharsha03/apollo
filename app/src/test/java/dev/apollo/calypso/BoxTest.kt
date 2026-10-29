package dev.apollo.calypso

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher

class BoxTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val keys = object : BoxKeys {
        override fun publicKey(): PublicKey = pair.public
        override fun unwrapper(): Unwrapper = CipherUnwrapper(Cipher.getInstance(Box.RSA).apply { init(Cipher.DECRYPT_MODE, pair.private as PrivateKey, Box.OAEP) })
    }

    @Test fun sealedItemsOpenWithTheBoxKey() {
        val e = Box.seal(keys.publicKey(), ItemKind.FILE, "passport", "Owner", "scan".toByteArray(), "p.pdf")
        val o = Box.open(e, keys.unwrapper())
        assertEquals("p.pdf", o.name)
        assertArrayEquals("scan".toByteArray(), o.data)
    }

    // The AAD binds label, kind and sender: Zeus's items must open only with the exact values they were sealed with.
    @Test fun aSwappedLabelDoesNotOpen() {
        val e = Box.seal(keys.publicKey(), ItemKind.SECRET, "wifi", "Iris", "pw".toByteArray())
        val swapped = Envelope(e.id, e.kind, "bank", e.from, e.at, e.wk, e.iv, e.ct)
        assertTrue(runCatching { Box.open(swapped, keys.unwrapper()) }.isFailure)
    }

    @Test fun theAadFormatIsZeuss() =
        assertEquals("calypso/v1\nab\nnote\nL\nIris", Box.aad("ab", ItemKind.NOTE, "L", "Iris").toString(Charsets.UTF_8))
}
