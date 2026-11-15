package dev.nightjar.app.feature.protection

import android.os.Build
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.domain.protection.OtaDetector
import dev.nightjar.app.domain.protection.OtaEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTA detection.
 *
 * The stored fingerprint is only advanced by [acknowledge], so the OTA prompt keeps showing until the user acts on
 * it — a prompt that vanished on the next app start would defeat its purpose.
 */
@Singleton
class OtaMonitor
    @Inject
    constructor(
        private val settings: SettingsStore,
    ) {
        suspend fun current(): OtaEvent =
            OtaDetector.check(
                storedFingerprint = settings.current().lastBuildFingerprint,
                currentFingerprint = Build.FINGERPRINT,
            )

        /** True when the OTA prompt should be shown: the fingerprint changed since the last acknowledged one. */
        suspend fun shouldPrompt(): Boolean = current().changed

        /** Records the running fingerprint, hiding the OTA prompt until the next OTA. */
        suspend fun acknowledge() = settings.setLastBuildFingerprint(Build.FINGERPRINT)
    }

/**
 * Device values for the install checklist. There is no USB debugging read: Protection status has no USB
 * debugging row.
 */
@Singleton
class DeviceChecks
    @Inject
    constructor() {
        /** The SDK level the install checklist compares against (: API 35). */
        fun sdkInt(): Int = Build.VERSION.SDK_INT
    }
