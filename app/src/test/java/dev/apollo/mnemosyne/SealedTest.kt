package dev.apollo.mnemosyne

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class SealedTest {
    private val key = Random(1).nextBytes(32)
    private val plain = Random(2).nextBytes(Sealed.CHUNK * 2 + 123)

    private fun seal(k: ByteArray = key): ByteArray = ByteArrayOutputStream().also { o -> Sealed.writer(o, k).use { it.write(plain) } }.toByteArray()
    private fun open(b: ByteArray, k: ByteArray = key) = Sealed.reader(ByteArrayInputStream(b), k).readBytes()

    @Test fun roundTrip() = assertArrayEquals(plain, open(seal()))

    @Test fun emptyRoundTrip() {
        val b = ByteArrayOutputStream().also { o -> Sealed.writer(o, key).close() }.toByteArray()
        assertEquals(0, open(b).size)
    }

    @Test fun wrongCodeSaysSo() = expect<Sealed.WrongCode> { open(seal(), Random(3).nextBytes(32)) }

    @Test fun anyTamperingFails() {
        val good = seal()
        expect<Sealed.Damaged> { open(good.copyOf(good.size - 10)) }                   // cut off
        expect<Sealed.Damaged> { open(good.copyOf(28 + 2 * (Sealed.CHUNK + 20))) }       // last chunk dropped
        expect<Sealed.Damaged> { open(good.copyOf().also { it[100] = (it[100] + 1).toByte() }) }
        expect<Sealed.Damaged> { open(good + byteArrayOf(0, 0, 0, 1)) }                // appended
    }

    @Test fun recoveryCode() {
        val code = RecoveryCode.encode(key)
        assertEquals(13, code.split(' ').size)
        assertArrayEquals(key, RecoveryCode.decode(code.uppercase().replace(' ', '-')))
        assertNull(RecoveryCode.decode(code.dropLast(1)))
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try { block(); fail("expected ${T::class.simpleName}") } catch (e: Throwable) { if (e !is T) throw e }
    }
}
