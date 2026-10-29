package dev.apollo.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class NetGateTest {
    private val hosts = setOf("apollo.example", "api.cloudhost.example")

    @Test fun onlyListedHostsOverHttpsOnTheDefaultPort() {
        assertTrue(NetGate.allows("https://apollo.example/v1/sync?since=3", hosts))
        assertTrue(NetGate.allows("https://API.CLOUDHOST.EXAMPLE:443/v1/servers", hosts))
        assertFalse(NetGate.allows("http://apollo.example/v1/sync", hosts))
        assertFalse(NetGate.allows("https://apollo.example:8443/", hosts))
        assertFalse(NetGate.allows("https://evil.apollo.example/", hosts))
        assertFalse(NetGate.allows("https://user@apollo.example/", hosts))
        assertFalse(NetGate.allows("https://example.com/", hosts))
    }

    @Test fun thePurposeNeverCarriesThePath() {
        assertEquals("Chat: your words", NetGate.purpose("apollo.example", "/v1/chat", "apollo.example"))
        assertEquals("A key you sent from Calypso", NetGate.purpose("apollo.example", "/v1/tomtom_key", "apollo.example"))
        assertEquals("Mini app or Live update: api.weather.gov", NetGate.purpose("api.weather.gov", "/points/42,-78", "apollo.example"))
    }

    // What the key signs is exactly the request's info part, and the request carries it.
    @Test fun theCertificateRequestIsSignedByItsKey() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        var signed = ByteArray(0)
        val der = Csr.der("apollo-phone", kp.public.encoded) { b -> signed = b; Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(b) }.sign() }
        assertEquals(0x30, der[0].toInt() and 0xff)
        assertTrue(signed.contentEquals(Csr.info("apollo-phone", kp.public.encoded)))
        assertTrue(Csr.pem(der).startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
        assertTrue(String(der, Charsets.ISO_8859_1).contains(String(signed, Charsets.ISO_8859_1)))
    }
}
