package dev.apollo.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.X509KeyManager

/**
 * A PKCS#10 certificate request, DER by hand (no BouncyCastle): CN only, no attributes, ecdsa-with-SHA256. Zeus's
 * lemnos/Keys.kt Csr, unchanged. Pure: [sign] is the Keystore key, so the encoding is tested on the JVM with a plain key.
 */
object Csr {
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().apply { write(tag); write(len(body.size)); write(body) }.toByteArray()
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    private val CN = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
    private val ECDSA_SHA256 = byteArrayOf(0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02)

    fun info(commonName: String, spki: ByteArray): ByteArray {
        val name = seq(tlv(0x31, seq(CN, tlv(0x0c, commonName.toByteArray(Charsets.UTF_8)))))
        return seq(byteArrayOf(0x02, 0x01, 0x00), name, spki, byteArrayOf(0xa0.toByte(), 0x00))
    }

    fun der(commonName: String, spki: ByteArray, sign: (ByteArray) -> ByteArray): ByteArray {
        val info = info(commonName, spki)
        return seq(info, seq(ECDSA_SHA256), tlv(0x03, byteArrayOf(0) + sign(info)))
    }

    fun pem(der: ByteArray): String =
        "-----BEGIN CERTIFICATE REQUEST-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END CERTIFICATE REQUEST-----\n"
}

/**
 * The phone's client certificate for the server's mTLS (server/apollo/devicecert.py, deploy/apollo.caddy). The key is a
 * Keystore P-256 key with no user auth (a background sync must connect) that never leaves the Keystore; pairing sends
 * its request and the signed chain goes back onto the key. Until then the phone offers no certificate and the server
 * (before deploy/mtls.sh) asks for none. A new alias, not Zeus's zeus.mtls (that one carries Zeus's CA).
 */
object DeviceCert {
    private const val STORE = "AndroidKeyStore"
    const val ALIAS = "apollo.mtls"
    private const val CN = "apollo-phone"

    private fun ks() = KeyStore.getInstance(STORE).apply { load(null) }

    /** Paired once the key carries a chain from the device CA (leaf + CA), not its own self-signed one. */
    fun paired(): Boolean = runCatching { (ks().getCertificateChain(ALIAS)?.size ?: 0) >= 2 }.getOrDefault(false)

    /** The request pairing sends: the key is made the first time. */
    fun request(): String {
        val store = ks()
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        // TLS signs through Conscrypt, which may ask for any of these.
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .build(),
                )
            }.generateKeyPair()
        }
        val key = store.getKey(ALIAS, null) as PrivateKey
        val spki = store.getCertificate(ALIAS).publicKey.encoded
        return Csr.pem(Csr.der(CN, spki) { b -> Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(b) }.sign() })
    }

    /** The chain pairing returned goes onto the key, after checking it is for this key and in date. */
    fun adopt(chainPem: String) {
        val chain = chainPem.byteInputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it).map { x -> x as X509Certificate } }
        require(chain.size >= 2) { "expected the certificate and the device CA" }
        val store = ks()
        val key = store.getKey(ALIAS, null) as PrivateKey
        require(chain[0].publicKey.encoded.contentEquals(store.getCertificate(ALIAS).publicKey.encoded)) { "that certificate is for another key" }
        chain[0].checkValidity()
        store.setKeyEntry(ALIAS, key, null, chain.toTypedArray())
    }

    /** Offers the Keystore key to the handshake, once it has its chain. Nothing is copied out of the Keystore. */
    val keyManager = object : X509KeyManager {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = ALIAS.takeIf { paired() }
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            runCatching { ks().getCertificateChain(ALIAS)?.map { it as X509Certificate }?.toTypedArray() }.getOrNull()
        override fun getPrivateKey(alias: String?): PrivateKey? = runCatching { ks().getKey(ALIAS, null) as PrivateKey }.getOrNull()
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
}
