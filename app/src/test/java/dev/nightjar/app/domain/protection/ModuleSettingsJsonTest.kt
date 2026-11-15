package dev.nightjar.app.domain.protection

import dev.nightjar.contract.SettingsKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The settings document the payload receives carries the certificate pin and the identity fields. */
class ModuleSettingsJsonTest {
    @Test
    fun `settings carry the certificate fingerprint`() {
        val json =
            ModuleSettingsJson.build(
                appPackage = "dev.nightjar.app",
                appCertSha256 = "AB:CD",
                safeMode = false,
                outgoingBlocked = emptyList(),
                outgoingEnabled = true,
                outgoingLogVisible = false,
                cbLog = true,
                cbAllChannels = false,
                suplGraceMin = 5,
            )
        assertTrue(json.contains("\"appPackage\":\"dev.nightjar.app\""))
        assertTrue(json.contains("\"appCertSha256\":\"AB:CD\""))
        assertTrue(json.contains("\"suplGraceMin\":5"))
        // The firewall is not part of the document.
        assertFalse(json.contains("firewallEnabledFor"), json)
        // The outgoing object uses the SettingsKeys names that the payload reads, with the empty blocked list of
        // the contract golden file.
        val outgoing =
            "\"${SettingsKeys.OUTGOING}\":{\"${SettingsKeys.OUTGOING_ENABLED}\":true," +
                "\"${SettingsKeys.OUTGOING_LOG_VISIBLE}\":false,\"${SettingsKeys.OUTGOING_BLOCKED}\":[]}"
        assertTrue(json.contains(outgoing), json)
    }

    @Test
    fun `the supl grace is clamped to the guard rail floor`() {
        val json =
            ModuleSettingsJson.build(
                appPackage = "p",
                appCertSha256 = "",
                safeMode = false,
                outgoingBlocked = emptyList(),
                outgoingEnabled = true,
                outgoingLogVisible = false,
                cbLog = true,
                cbAllChannels = false,
                suplGraceMin = 1,
            )
        assertTrue(json.contains("\"suplGraceMin\":5"))
    }

    @Test
    fun `the certificate fingerprint is colon separated upper case hex`() {
        val fingerprint = ModuleSettingsJson.certFingerprint("nightjar".toByteArray())
        // 32 bytes, colon separated, upper-case hex (example format).
        assertEquals(32 * 3 - 1, fingerprint.length)
        assertTrue(fingerprint.split(':').all { it.length == 2 && it.all { c -> c in "0123456789ABCDEF" } }, fingerprint)
    }

    @Test
    fun `the settings factory always writes the identity fields`() {
        val json =
            ModuleSettingsFactory(
                appPackage = { "dev.nightjar" },
                // An unresolvable certificate must never be sent as "" or the payload withholds everything.
                appCertSha256 = { "" },
                outgoingBlocked = { listOf("com.example.a", "com.example.b") },
                current = {
                    dev.nightjar.app.core.settings
                        .Settings(safeMode = true)
                },
            ).build()
        assertTrue(json.contains("\"appPackage\":\"dev.nightjar\""), json)
        assertTrue(json.contains("\"appCertSha256\":\"UNAVAILABLE\""), json)
        // The blocked packages are written in the order given.
        assertTrue(json.contains("\"${SettingsKeys.OUTGOING_BLOCKED}\":[\"com.example.a\",\"com.example.b\"]"), json)
        assertTrue(json.contains("\"safeMode\":true"), json)
    }
}
