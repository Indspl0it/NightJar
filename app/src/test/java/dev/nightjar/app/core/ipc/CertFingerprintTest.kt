package dev.nightjar.app.core.ipc

import dev.nightjar.app.domain.protection.ModuleSettingsJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pin written into `settings.json` is upper-case, colon separated, and never empty. */
class CertFingerprintTest {
    @Test
    fun `a digest becomes upper case colon separated hex`() {
        val digest = ByteArray(32) { it.toByte() }
        val fingerprint = CertFingerprint.ofDigest(digest)
        assertEquals(32 * 3 - 1, fingerprint.length)
        assertEquals("00:01:02", fingerprint.take(8))
        assertEquals("1D:1E:1F", fingerprint.takeLast(8))
        assertTrue(fingerprint.none { it in 'a'..'f' }, "must be upper case")
    }

    @Test
    fun `a negative byte is formatted as two digits`() {
        assertEquals("80:FF", CertFingerprint.ofDigest(byteArrayOf(0x80.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `the certificate form hashes the DER bytes`() {
        val der = "nightjar".toByteArray()
        val expected =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(der)
                .joinToString(":") { "%02X".format(it) }
        assertEquals(expected, CertFingerprint.ofCertificate(der))
        assertFalse(expected.isEmpty())
    }

    @Test
    fun `the domain settings builder uses the same convention`() {
        val der = byteArrayOf(0x01, 0x02, 0x7F, 0xFF.toByte())
        assertEquals(CertFingerprint.ofCertificate(der), ModuleSettingsJson.certFingerprint(der))
    }
}
