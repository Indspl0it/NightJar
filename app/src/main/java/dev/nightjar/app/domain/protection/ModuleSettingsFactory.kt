package dev.nightjar.app.domain.protection

import dev.nightjar.app.core.settings.Settings

/**
 * The single builder of `settings.json`.
 *
 * `EventProvider.settingsJson()` and `SettingsSync` both go through here, so the two copies of the document that the
 * payload receives cannot drift: a change to any mirrored setting rewrites the *whole* object, including `appPackage`
 * and `appCertSha256`. Writing only the changed keys dropped them and left the payload with an empty certificate pin
 * (it then withholds every message).
 *
 * The document has no firewall key: the firewall is turned on with the control call `ENABLE_FIREWALL`, and the
 * payload reports the state as `firewallEnabled` in its `hello` reply.
 *
 * The ambient inputs are functions so the class stays free of Android types and is unit-testable; Dagger cannot
 * bind Kotlin function types, so the instance comes from a `@Provides` in `AppModule` rather than `@Inject`.
 */
class ModuleSettingsFactory(
    private val appPackage: () -> String,
    private val appCertSha256: () -> String,
    private val outgoingBlocked: () -> List<String>,
    private val current: () -> Settings,
) {
    /** The document for the current settings snapshot. */
    fun build(): String = build(current())

    fun build(current: Settings): String =
        ModuleSettingsJson.build(
            appPackage = appPackage(),
            // An empty fingerprint makes the payload withhold every message, so a missing one becomes an
            // explicit marker that can only mismatch and is therefore diagnosable.
            appCertSha256 = appCertSha256().ifEmpty { UNAVAILABLE },
            safeMode = current.safeMode,
            // The packages that the user blocked from sending SMS.
            outgoingBlocked = outgoingBlocked(),
            outgoingEnabled = current.outgoingEnabled,
            outgoingLogVisible = current.outgoingLogVisible,
            cbLog = current.cbLog,
            cbAllChannels = current.cbAllChannels,
            suplGraceMin = current.detectSuplGraceMin,
            debugLoggingUntilMs = current.debugLoggingUntil,
        )

    companion object {
        /** Never a valid fingerprint: better a diagnosable "content withheld" than an empty pin that reads as one. */
        const val UNAVAILABLE = "UNAVAILABLE"
    }
}
