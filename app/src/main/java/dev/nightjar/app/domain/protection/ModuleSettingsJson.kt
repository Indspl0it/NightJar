package dev.nightjar.app.domain.protection

import dev.nightjar.contract.Schemas
import dev.nightjar.contract.SettingsKeys
import java.security.MessageDigest

/**
 * Builds the settings document that the payload receives over the EventProvider handshake. NightJar
 * is an Xposed module, so nothing is written to a module directory.
 *
 * The payload pins the app's signing certificate from this document, so a wrong or missing `appCertSha256`
 * makes the payload refuse message content. The value therefore comes from the installed package's signing
 * certificate, not from a stored copy.
 *
 * The firewall is not part of this document: it is turned on with the control call `ENABLE_FIREWALL` (see
 * `ModuleSettingsFactory`).
 *
 * `outgoing.blocked` lists the packages that the user blocked from sending SMS. The payload `OutboundGate`
 * blocks a send from a listed package and allows every other send.
 */
object ModuleSettingsJson {
    // One parameter per field of the settings document; the payload reads the JSON, not this signature.
    @Suppress("LongParameterList")
    fun build(
        appPackage: String,
        appCertSha256: String,
        safeMode: Boolean,
        outgoingBlocked: List<String>,
        outgoingEnabled: Boolean,
        outgoingLogVisible: Boolean,
        cbLog: Boolean,
        cbAllChannels: Boolean,
        suplGraceMin: Int,
        debugLoggingUntilMs: Long = 0,
    ): String =
        buildString {
            append('{')
            field("schema", Schemas.SETTINGS.toString())
            append(',')
            field("appPackage", quote(appPackage))
            append(',')
            field("appCertSha256", quote(appCertSha256))
            append(',')
            field("safeMode", safeMode.toString())
            append(',')
            // The outgoing keys come from:contract. the blocked list holds package names.
            append(quote(SettingsKeys.OUTGOING)).append(":{")
            field(SettingsKeys.OUTGOING_ENABLED, outgoingEnabled.toString())
            append(',')
            field(SettingsKeys.OUTGOING_LOG_VISIBLE, outgoingLogVisible.toString())
            append(',')
            field(SettingsKeys.OUTGOING_BLOCKED, outgoingBlocked.joinToString(",", "[", "]") { quote(it) })
            append("},")
            append("\"cb\":{")
            field("log", cbLog.toString())
            append(',')
            field("allChannels", cbAllChannels.toString())
            append("},")
            append("\"debugLogging\":{")
            field("until", debugLoggingUntilMs.toString())
            append("},")
            append("\"detect\":{")
            field("suplGraceMin", suplGraceMin.coerceIn(5, 60).toString())
            append('}')
            append('}')
        }

    private fun StringBuilder.field(
        name: String,
        value: String,
    ) {
        append(quote(name)).append(':').append(value)
    }

    /** Minimal JSON string escaping for the values this file writes (package names, hex digests). */
    private fun quote(value: String): String =
        buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

    /** Colon-separated upper-case hex, the form the payload compares against (example). */
    fun certFingerprint(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
