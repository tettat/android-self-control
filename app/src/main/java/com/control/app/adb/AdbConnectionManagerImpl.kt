package com.control.app.adb

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.conscrypt.Conscrypt
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Singleton ADB connection manager that extends libadb-android's AbsAdbConnectionManager.
 * Manages RSA key pair generation, storage, pairing, and connection to the device's
 * own ADB daemon via localhost.
 */
class AdbConnectionManagerImpl private constructor(context: Context) : AbsAdbConnectionManager() {

    companion object {
        private const val TAG = "AdbConnectionMgr"
        private const val KEYSTORE_FILE = "adb_key.p12"
        private const val KEYSTORE_PASSWORD = "control-adb"
        private const val KEY_ALIAS = "adb"
        private const val KEY_DIR = "adb_keys"

        @Volatile
        private var instance: AdbConnectionManagerImpl? = null

        fun getInstance(context: Context): AdbConnectionManagerImpl {
            return instance ?: synchronized(this) {
                instance ?: AdbConnectionManagerImpl(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val _privateKey: PrivateKey
    private val _certificate: Certificate

    init {
        // Install Conscrypt as TLS provider for ADB pairing support
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install Conscrypt provider: ${e.message}")
        }

        setApi(Build.VERSION.SDK_INT)

        val keyDir = File(context.filesDir, KEY_DIR)
        keyDir.mkdirs()
        val keyStoreFile = File(keyDir, KEYSTORE_FILE)

        val pair = loadOrGenerateKeyPair(keyStoreFile)
        _privateKey = pair.first
        _certificate = pair.second
    }

    private fun loadOrGenerateKeyPair(keyStoreFile: File): Pair<PrivateKey, Certificate> {
        // Try loading existing key pair from PKCS12 keystore
        if (keyStoreFile.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                keyStoreFile.inputStream().use { ks.load(it, KEYSTORE_PASSWORD.toCharArray()) }
                val key = ks.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as? PrivateKey
                val cert = ks.getCertificate(KEY_ALIAS)
                if (key != null && cert != null) {
                    Log.d(TAG, "Loaded existing ADB key pair")
                    return Pair(key, cert)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load existing key pair, regenerating: ${e.message}")
                keyStoreFile.delete()
            }
        }

        // Generate new RSA 2048-bit key pair
        Log.d(TAG, "Generating new ADB key pair")
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val cert = generateSelfSignedCertificate(keyPair)

        // Persist to PKCS12 keystore
        try {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, KEYSTORE_PASSWORD.toCharArray())
            ks.setKeyEntry(KEY_ALIAS, keyPair.private, KEYSTORE_PASSWORD.toCharArray(), arrayOf(cert))
            keyStoreFile.outputStream().use { ks.store(it, KEYSTORE_PASSWORD.toCharArray()) }
            Log.d(TAG, "Saved new ADB key pair to keystore")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save key pair to keystore: ${e.message}", e)
            // Keys are still usable in memory for this session
        }

        return Pair(keyPair.private, cert)
    }

    /**
     * Generates a self-signed X.509 certificate suitable for ADB authentication.
     *
     * This uses raw DER encoding to create a minimal self-signed certificate,
     * which is the most portable approach across all Android versions without
     * requiring any optional/internal APIs.
     */
    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val subject = "CN=Control ADB Client"
        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        val notAfter = Date(now + 25L * 365 * 24 * 3600 * 1000) // 25 years
        val serialNumber = BigInteger(64, SecureRandom())

        // Build the TBSCertificate (to-be-signed certificate) in DER format
        val tbsCert = buildTbsCertificate(
            serialNumber = serialNumber,
            issuerAndSubject = subject,
            notBefore = notBefore,
            notAfter = notAfter,
            publicKey = keyPair.public
        )

        // Sign the TBSCertificate with SHA256withRSA
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(keyPair.private)
        signature.update(tbsCert)
        val signatureBytes = signature.sign()

        // Build the complete certificate
        val certDer = buildCertificate(tbsCert, signatureBytes)

        // Parse the DER bytes into an X509Certificate object
        val certFactory = CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(certDer.inputStream()) as X509Certificate
    }

    // --- ASN.1 DER encoding helpers ---

    private fun buildTbsCertificate(
        serialNumber: BigInteger,
        issuerAndSubject: String,
        notBefore: Date,
        notAfter: Date,
        publicKey: PublicKey
    ): ByteArray {
        val components = mutableListOf<ByteArray>()

        // Version: v3 (value 2), explicit tag [0]
        components.add(derExplicit(0, derInteger(BigInteger.valueOf(2))))

        // Serial number
        components.add(derInteger(serialNumber))

        // Signature algorithm: SHA256withRSA (OID 1.2.840.113549.1.1.11)
        components.add(derSequence(
            derOid(byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(),
                0xF7.toByte(), 0x0D.toByte(), 0x01.toByte(), 0x01.toByte(), 0x0B.toByte())),
            derNull()
        ))

        // Issuer (same as subject for self-signed)
        components.add(buildRdnSequence(issuerAndSubject))

        // Validity
        components.add(derSequence(
            derUtcTime(notBefore),
            derGeneralizedTime(notAfter)
        ))

        // Subject
        components.add(buildRdnSequence(issuerAndSubject))

        // Subject public key info (use the encoded form from the key)
        components.add(publicKey.encoded) // Already DER-encoded SubjectPublicKeyInfo

        return derSequence(*components.toTypedArray())
    }

    private fun buildCertificate(tbsCertDer: ByteArray, signatureBytes: ByteArray): ByteArray {
        // Signature algorithm: SHA256withRSA
        val sigAlg = derSequence(
            derOid(byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(),
                0xF7.toByte(), 0x0D.toByte(), 0x01.toByte(), 0x01.toByte(), 0x0B.toByte())),
            derNull()
        )

        // Signature value as BIT STRING (prepend 0x00 for unused bits)
        val sigBitString = derBitString(signatureBytes)

        return derSequence(tbsCertDer, sigAlg, sigBitString)
    }

    private fun buildRdnSequence(dn: String): ByteArray {
        // Parse a simple "CN=value" distinguished name
        val cnValue = if (dn.startsWith("CN=")) dn.substring(3) else dn
        // OID for CN (Common Name): 2.5.4.3
        val cnOid = derOid(byteArrayOf(0x55, 0x04, 0x03))
        val cnVal = derUtf8String(cnValue)
        val atv = derSequence(cnOid, cnVal)      // AttributeTypeAndValue
        val rdn = derSet(atv)                     // RelativeDistinguishedName
        return derSequence(rdn)                   // RDNSequence
    }

    // DER primitive encoders

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val lenBytes = derLength(content.size)
        val result = ByteArray(1 + lenBytes.size + content.size)
        result[0] = tag.toByte()
        lenBytes.copyInto(result, 1)
        content.copyInto(result, 1 + lenBytes.size)
        return result
    }

    private fun derLength(length: Int): ByteArray {
        return when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
            length < 0x10000 -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte())
            else -> byteArrayOf(
                0x83.toByte(),
                (length shr 16).toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte()
            )
        }
    }

    private fun derSequence(vararg items: ByteArray): ByteArray {
        val content = items.fold(ByteArray(0)) { acc, item -> acc + item }
        return derTag(0x30, content)
    }

    private fun derSet(vararg items: ByteArray): ByteArray {
        val content = items.fold(ByteArray(0)) { acc, item -> acc + item }
        return derTag(0x31, content)
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return derTag(0x02, bytes)
    }

    private fun derOid(encodedOid: ByteArray): ByteArray {
        return derTag(0x06, encodedOid)
    }

    private fun derNull(): ByteArray {
        return byteArrayOf(0x05, 0x00)
    }

    private fun derBitString(data: ByteArray): ByteArray {
        // Prepend the "unused bits" byte (0)
        val content = ByteArray(1 + data.size)
        content[0] = 0x00
        data.copyInto(content, 1)
        return derTag(0x03, content)
    }

    private fun derUtf8String(value: String): ByteArray {
        return derTag(0x0C, value.toByteArray(Charsets.UTF_8))
    }

    private fun derExplicit(tagNum: Int, content: ByteArray): ByteArray {
        // Context-specific, constructed, explicit tag
        return derTag(0xA0 or tagNum, content)
    }

    private fun derUtcTime(date: Date): ByteArray {
        @Suppress("DEPRECATION")
        val formatted = String.format(
            "%02d%02d%02d%02d%02d%02dZ",
            (date.year % 100), date.month + 1, date.date,
            date.hours, date.minutes, date.seconds
        )
        return derTag(0x17, formatted.toByteArray(Charsets.US_ASCII))
    }

    private fun derGeneralizedTime(date: Date): ByteArray {
        @Suppress("DEPRECATION")
        val formatted = String.format(
            "%04d%02d%02d%02d%02d%02dZ",
            date.year + 1900, date.month + 1, date.date,
            date.hours, date.minutes, date.seconds
        )
        return derTag(0x18, formatted.toByteArray(Charsets.US_ASCII))
    }

    // --- AbsAdbConnectionManager overrides ---

    override fun getPrivateKey(): PrivateKey = _privateKey

    override fun getCertificate(): Certificate = _certificate

    override fun getDeviceName(): String = "Control-${Build.MODEL}"
}
