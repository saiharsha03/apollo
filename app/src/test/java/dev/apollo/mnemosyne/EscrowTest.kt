package dev.apollo.mnemosyne

import dev.apollo.calypso.Box
import dev.apollo.calypso.CipherUnwrapper
import dev.apollo.calypso.Envelope
import dev.apollo.calypso.ItemKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EscrowTest {
    private fun rsa() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    // An item escrowed when sealed opens on a "new phone" after the code's key re-wraps it, label and content unchanged.
    @Test fun anEscrowedItemOpensOnANewPhone() {
        val oldPhone = rsa(); val newPhone = rsa()
        val backupKey = ByteArray(32) { it.toByte() }
        val pair = Escrow.newPair(Escrow.escrowKey(backupKey))
        val escrowed = HashMap<String, ByteArray>()
        Box.keyTap = { id, key -> escrowed[id] = Escrow.wrap(Escrow.publicKey(pair.publicDer), key) }
        val e = try { Box.seal(oldPhone.public, ItemKind.SECRET, "wifi", "Owner", "hunter2".toByteArray()) } finally { Box.keyTap = null }
        val priv = Escrow.openPrivate(pair.sealedPrivate, Escrow.escrowKey(backupKey))
        val moved = Envelope(e.id, e.kind, e.label, e.from, e.at, Escrow.rewrap(priv, escrowed.getValue(e.id), newPhone.public), e.iv, e.ct)
        val u = CipherUnwrapper(Cipher.getInstance(Box.RSA).apply { init(Cipher.DECRYPT_MODE, newPhone.private, Box.OAEP) })
        assertArrayEquals("hunter2".toByteArray(), Box.open(moved, u).data)
    }

    @Test fun theWrongCodeDoesNotOpenTheEscrow() {
        val pair = Escrow.newPair(Escrow.escrowKey(ByteArray(32)))
        assertTrue(runCatching { Escrow.openPrivate(pair.sealedPrivate, Escrow.escrowKey(ByteArray(32) { 1 })) }.isFailure)
    }

    // The Calypso code (not kept on the phone) opens the escrow; its 52-letter form round-trips; a v1 label doesn't fit.
    @Test fun theCalypsoCodeOpensTheEscrowAndOnlyAsV2() {
        val secret = ByteArray(32) { (it * 3).toByte() }
        val pair = Escrow.newPair(Escrow.escrowKey(secret))
        val typed = RecoveryCode.decode(RecoveryCode.encode(secret).uppercase())!!
        Escrow.openPrivate(pair.sealedPrivate, Escrow.escrowKey(typed), Escrow.AAD_V2)
        assertTrue(runCatching { Escrow.openPrivate(pair.sealedPrivate, Escrow.escrowKey(typed), Escrow.AAD_V1) }.isFailure)
    }

    // "Include Calypso" keeps nothing until he confirms; the code it shows is the one that opens what it would keep.
    @Test fun aPreparedCodeOpensItsOwnEscrow() {
        val p = CalypsoEscrow.prepare()
        Escrow.openPrivate(java.util.Base64.getDecoder().decode(p.priv), Escrow.escrowKey(RecoveryCode.decode(p.code)!!), Escrow.AAD_V2)
    }

    // A restore never copies in prefs a backup never carries: a planted escrow (on, an attacker's key) stays out.
    @Test fun aRestoreSkipsEscrowAndCalypsoPrefs() {
        for (n in listOf("calypso_escrow.xml", "calypso.xml", "calypso_drops.xml", "mnemosyne.xml", "secure_clip.xml"))
            assertTrue(n, Mnemosyne.SKIP_PREFS.matches(n))
    }

    @Test fun zeusPhrasesRoundTripAndCatchATypo() {
        val e = ByteArray(32) { (it * 7).toByte() }
        val words = Phrase.encode(e)
        assertEquals(24, words.size)
        assertArrayEquals(e, Phrase.decode(words.joinToString(" ")))
        val swapped = (listOf(words[1], words[0]) + words.drop(2)).joinToString(" ")
        assertTrue(words[0] == words[1] || runCatching { Phrase.decode(swapped) }.isFailure)
    }

    // A file written the way Zeus's Zbk.writer wrote it reads back, and the wrong key is told apart.
    @Test fun zeusBackupFilesRead() {
        val key = ZeusKeys.backupKey(ByteArray(32) { 3 })
        val salt = ByteArray(32) { 9 }
        val raw = """{"v":1,"id":"x","created":1,"salt":"${java.util.Base64.getEncoder().encodeToString(salt)}","check":"${ZeusKeys.check(key)}","chunk":1048576}""".toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ZBK1".toByteArray()); out.write(ByteBuffer.allocate(4).putInt(raw.size).array()); out.write(raw)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ZeusKeys.hkdf(key, salt, "file key"), "AES"), GCMParameterSpec(128, ByteBuffer.allocate(12).putLong(0).putInt(1).array()))
        c.updateAAD(MessageDigest.getInstance("SHA-256").digest(raw))
        val ct = c.doFinal("hello zeus".toByteArray())
        out.write(ByteBuffer.allocate(4).putInt(ct.size).array()); out.write(ct)
        assertEquals("hello zeus", Zbk.reader(ByteArrayInputStream(out.toByteArray()), key).readBytes().toString(Charsets.UTF_8))
        assertTrue(runCatching { Zbk.reader(ByteArrayInputStream(out.toByteArray()), ZeusKeys.backupKey(ByteArray(32))) }.exceptionOrNull() is Zbk.WrongKey)
    }
}
