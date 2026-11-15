package dev.nightjar.app.core.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.ui.theme.NjAccent
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the settings catalogue.
 *
 * One typed accessor per settings key: a flat table that must mirror the catalogue, not an abstraction to split.
 */
@Suppress("TooManyFunctions")
interface SettingsStore {
    /** The current snapshot; emits on every change. Never throws for a missing key (defaults apply). */
    val settings: StateFlow<Settings>

    /**
     * True once the first snapshot has been read from disk.
     *
     * Before that, [settings] carries the default object: a reader that acts on it would see `appLock = false` or the
     * wrong theme. Consumers that decide from the snapshot must wait for this first.
     */
    val loaded: StateFlow<Boolean>
        get() = ALWAYS_LOADED

    suspend fun current(): Settings = settings.value

    /**
     * Applies [transform] and returns the user-visible changes it produced, so the caller can push them to the payload
     * (`SettingsSync`) and the audit log. Values are clamped by the individual setters, never by the caller.
     */
    suspend fun edit(transform: (Settings) -> Settings): List<SettingsChange>

    suspend fun setMode(mode: UiMode): List<SettingsChange>

    suspend fun setTheme(theme: ThemeMode): List<SettingsChange>

    suspend fun setDynamicColor(enabled: Boolean): List<SettingsChange>

    suspend fun setAutoReview(enabled: Boolean): List<SettingsChange>

    suspend fun setNotifySuspicious(enabled: Boolean): List<SettingsChange>

    suspend fun setNotifyNotable(enabled: Boolean): List<SettingsChange>

    suspend fun setNotifyCb(enabled: Boolean): List<SettingsChange>

    suspend fun setNotifyDigest(mode: DigestMode): List<SettingsChange>

    suspend fun setNotifyRateLimit(minutes: Int): List<SettingsChange>

    suspend fun setDetectSilentCount(count: Int): List<SettingsChange>

    suspend fun setDetectSilentWindow(minutes: Int): List<SettingsChange>

    suspend fun setDetectSilentNewSender(enabled: Boolean): List<SettingsChange>

    suspend fun setDetectSilentRoaming(enabled: Boolean): List<SettingsChange>

    suspend fun setDetectSuplGrace(minutes: Int): List<SettingsChange>

    /** Sets or clears (`null`) the per-class severity override (`severity.<classId>`). */
    suspend fun setSeverityOverride(
        classId: ClassId,
        severity: Severity?,
    ): List<SettingsChange>

    suspend fun setOutgoingEnabled(enabled: Boolean): List<SettingsChange>

    suspend fun setOutgoingLogVisible(enabled: Boolean): List<SettingsChange>

    suspend fun setCbLog(enabled: Boolean): List<SettingsChange>

    suspend fun setCbAllChannels(enabled: Boolean): List<SettingsChange>

    suspend fun setAppLock(enabled: Boolean): List<SettingsChange>

    suspend fun setLockTimeout(timeout: LockTimeout): List<SettingsChange>

    suspend fun setMaskNumbers(enabled: Boolean): List<SettingsChange>

    suspend fun setHideInNotifications(enabled: Boolean): List<SettingsChange>

    suspend fun setBlockScreenshots(enabled: Boolean): List<SettingsChange>

    suspend fun setRetention(retention: Retention): List<SettingsChange>

    /** Enables debug logging for 24 h, or turns it off. */
    suspend fun setDebugLogging(enabled: Boolean): List<SettingsChange>

    suspend fun setSafeMode(enabled: Boolean): List<SettingsChange>

    suspend fun setOnboardingDone(done: Boolean): List<SettingsChange>

    suspend fun setLastBuildFingerprint(fingerprint: String): List<SettingsChange>

    /** Shows delivery reports in the message list (`messages.showDeliveryReports`). */
    suspend fun setShowDeliveryReports(enabled: Boolean): List<SettingsChange> =
        edit { it.copy(showDeliveryReports = enabled) }

    /** Appearance settings (`ui.*`). Default methods, so a fake store needs no change. */
    suspend fun setAccent(accent: NjAccent): List<SettingsChange> = edit { it.copy(accent = accent) }

    suspend fun setTrueBlack(enabled: Boolean): List<SettingsChange> = edit { it.copy(trueBlack = enabled) }

    suspend fun setFloatingBar(enabled: Boolean): List<SettingsChange> = edit { it.copy(floatingBar = enabled) }

    suspend fun setListAnimations(enabled: Boolean): List<SettingsChange> = edit { it.copy(listAnimations = enabled) }

    suspend fun setListBounce(enabled: Boolean): List<SettingsChange> = edit { it.copy(listBounce = enabled) }

    suspend fun setReduceMotion(enabled: Boolean): List<SettingsChange> = edit { it.copy(reduceMotion = enabled) }

    suspend fun setHaptics(enabled: Boolean): List<SettingsChange> = edit { it.copy(haptics = enabled) }

    /** Turns debug logging off once its 24 h window has passed; returns the change when it did. */
    suspend fun enforceDebugLoggingWindow(now: Long): SettingsChange?

    companion object {
        /** Default [loaded] for stores that are synchronous (test fakes). */
        private val ALWAYS_LOADED = MutableStateFlow(true)
    }
}

/**
 * DataStore-backed store.
 *
 * Every write goes through [edit], which compares the new snapshot with the stored one field by field, so a change
 * record exists only for keys the user actually changed — no spurious `SETTINGS_CHANGE` rows when the UI re-applies the
 * same value. The audit row is written inside the same suspend call, before the caller sees the result.
 *
 * One getter/setter per catalogue key, mirroring [SettingsStore] and the settings catalogue.
 */
@Suppress("TooManyFunctions")
@Singleton
class DataStoreSettingsStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val auditProvider: dagger.Lazy<AuditLog>,
        private val clock: Clock,
        private val scope: CoroutineScope,
    ) : SettingsStore {
        constructor(
            dataStore: DataStore<Preferences>,
            audit: AuditLog,
            clock: Clock,
            scope: CoroutineScope,
        ) : this(dataStore, dagger.Lazy { audit }, clock, scope)

        /**
         * Resolved on first use. The audit log sits on the encrypted database, and this store is built by the logger,
         * which the Direct Boot ingest path needs before the first unlock, when that database cannot be opened.
         */
        private val audit: AuditLog get() = auditProvider.get()

        /**
         * `write` replaces every key from a snapshot read before it, so two overlapping edits (a switch and the debug
         * window expiry, say) would each write back the other's old value. One at a time.
         */
        private val writeLock = Mutex()

        /** Set by the [settings] pipeline: true after the first successful read, or after a corrupt store. */
        private val loadedState = MutableStateFlow(false)

        override val settings: StateFlow<Settings> =
            dataStore.data
                .map(::read)
                .onEach { loadedState.value = true }
                // A corrupt or unreadable file would otherwise cancel this app-scope coroutine with an uncaught exception
                // and crash the process; `read` already turns missing keys into defaults, so an empty store does the same.
                // Before the first unlock the file is unreadable (an IOException), and it becomes readable later in the same
                // process: keep asking instead of ending the flow, or the process would run on defaults for good.
                .retryWhen { cause, _ ->
                    (cause is IOException && cause !is CorruptionException).also { if (it) delay(READ_RETRY_MS) }
                }.catch {
                    // A corrupt store (or any error that is not "not readable yet") runs on the defaults for good.
                    loadedState.value = true
                    emit(read(emptyPreferences()))
                }.conflate()
                .distinctUntilChanged()
                .onEach { snapshot -> enforceWindowInMemory(snapshot) }
                .stateIn(scope, SharingStarted.Eagerly, Settings())

        // The first successful read flips it, a corrupt store still counts as loaded (the defaults then apply, as
        // `read` already does for a missing key). A store that is not readable yet (before the first unlock) stays
        // "not loaded": the defaults in [settings] then must not be acted on (app lock, settings push).
        override val loaded: StateFlow<Boolean> = loadedState

        override suspend fun current(): Settings = read(dataStore.data.first())

        override suspend fun edit(transform: (Settings) -> Settings): List<SettingsChange> {
            val changes =
                writeLock.withLock {
                    val before = current()
                    val after = transform(before)
                    diff(before, after).also { if (it.isNotEmpty()) write(after) }
                }
            if (changes.isEmpty()) return emptyList()
            // App state that the user does not set (onboarding, the last build seen) is not a user settings change.
            changes.filter { it.key !in NOT_AUDITED }.forEach { change ->
                audit.recordValue(
                    action = AuditAction.SETTINGS_CHANGE,
                    actor = AuditActor.USER,
                    target = change.key,
                    before = change.before,
                    after = change.after,
                )
            }
            return changes
        }

        override suspend fun setMode(mode: UiMode) = edit { it.copy(mode = mode) }

        override suspend fun setTheme(theme: ThemeMode) = edit { it.copy(theme = theme) }

        override suspend fun setDynamicColor(enabled: Boolean) = edit { it.copy(dynamicColor = enabled) }

        override suspend fun setAutoReview(enabled: Boolean) = edit { it.copy(autoReview = enabled) }

        override suspend fun setNotifySuspicious(enabled: Boolean) = edit { it.copy(notifySuspicious = enabled) }

        override suspend fun setNotifyNotable(enabled: Boolean) = edit { it.copy(notifyNotable = enabled) }

        override suspend fun setNotifyCb(enabled: Boolean) = edit { it.copy(notifyCb = enabled) }

        override suspend fun setNotifyDigest(mode: DigestMode) = edit { it.copy(notifyDigest = mode) }

        override suspend fun setNotifyRateLimit(minutes: Int) =
            edit { it.copy(notifyRateLimitMin = Settings.clampRateLimit(minutes)) }

        override suspend fun setDetectSilentCount(count: Int) =
            edit { it.copy(detectSilentCount = Settings.clampSilentCount(count)) }

        override suspend fun setDetectSilentWindow(minutes: Int) =
            edit { it.copy(detectSilentWindowMin = Settings.clampSilentWindow(minutes)) }

        override suspend fun setDetectSilentNewSender(enabled: Boolean) =
            edit { it.copy(detectSilentNewSender = enabled) }

        override suspend fun setDetectSilentRoaming(enabled: Boolean) =
            edit { it.copy(detectSilentRoaming = enabled) }

        override suspend fun setDetectSuplGrace(minutes: Int) =
            edit { it.copy(detectSuplGraceMin = Settings.clampSuplGrace(minutes)) }

        override suspend fun setSeverityOverride(
            classId: ClassId,
            severity: Severity?,
        ) = edit { current ->
            val map = current.severityOverrides.toMutableMap()
            if (severity == null) map.remove(classId) else map[classId] = severity
            current.copy(severityOverrides = map.toMap())
        }

        override suspend fun setOutgoingEnabled(enabled: Boolean) = edit { it.copy(outgoingEnabled = enabled) }

        override suspend fun setOutgoingLogVisible(enabled: Boolean) = edit { it.copy(outgoingLogVisible = enabled) }

        override suspend fun setCbLog(enabled: Boolean) = edit { it.copy(cbLog = enabled) }

        override suspend fun setCbAllChannels(enabled: Boolean) = edit { it.copy(cbAllChannels = enabled) }

        override suspend fun setAppLock(enabled: Boolean) = edit { it.copy(appLock = enabled) }

        override suspend fun setLockTimeout(timeout: LockTimeout) = edit { it.copy(lockTimeout = timeout) }

        override suspend fun setMaskNumbers(enabled: Boolean) = edit { it.copy(maskNumbers = enabled) }

        override suspend fun setHideInNotifications(enabled: Boolean) = edit { it.copy(hideInNotifications = enabled) }

        override suspend fun setBlockScreenshots(enabled: Boolean) = edit { it.copy(blockScreenshots = enabled) }

        override suspend fun setRetention(retention: Retention) = edit { it.copy(retention = retention) }

        override suspend fun setDebugLogging(enabled: Boolean) =
            edit { current ->
                current.copy(
                    debugLogging = enabled,
                    debugLoggingUntil = if (enabled) clock.now() + Settings.DEBUG_LOGGING_WINDOW_MS else 0,
                )
            }

        override suspend fun setSafeMode(enabled: Boolean) = edit { it.copy(safeMode = enabled) }

        override suspend fun setShowDeliveryReports(enabled: Boolean) = edit { it.copy(showDeliveryReports = enabled) }

        override suspend fun setOnboardingDone(done: Boolean) = edit { it.copy(onboardingDone = done) }

        override suspend fun setLastBuildFingerprint(fingerprint: String) =
            edit { it.copy(lastBuildFingerprint = fingerprint) }

        override suspend fun enforceDebugLoggingWindow(now: Long): SettingsChange? {
            writeLock.withLock {
                val before = current()
                if (!before.debugLogging || before.debugLoggingUntil > now) return null
                write(before.copy(debugLogging = false, debugLoggingUntil = 0))
            }
            val change = SettingsChange("advanced.debugLogging", true, false)
            audit.recordValue(
                action = AuditAction.SETTINGS_CHANGE,
                actor = AuditActor.SYSTEM,
                target = change.key,
                before = change.before,
                after = change.after,
            )
            return change
        }

        /** The 24 h window is enforced lazily; this keeps a live collector from holding a stale "on". */
        private fun enforceWindowInMemory(snapshot: Settings) {
            if (!snapshot.debugLogging) return
            val now = clock.now()
            if (snapshot.debugLoggingUntil > now) return
            // Persist off the reading path; the next emission carries the change.
            scope.launch { enforceDebugLoggingWindow(now) }
        }

        private suspend fun write(value: Settings) {
            dataStore.edit { p ->
                p[K_MODE] = value.mode.wire
                p[K_THEME] = value.theme.wire
                p[K_DYNAMIC_COLOR] = value.dynamicColor
                p[K_ACCENT] = value.accent.wire
                p[K_TRUE_BLACK] = value.trueBlack
                p[K_FLOATING_BAR] = value.floatingBar
                // The bar has no blur now, so "Blur effects" is gone. Drop the old key of an older install.
                p.remove(K_OLD_BLUR_EFFECTS)
                // The weekly summary has one control (notify.digest). Drop the old switch of an older install.
                p.remove(K_OLD_NOTIFY_FIREWALL)
                p[K_LIST_ANIMATIONS] = value.listAnimations
                p[K_LIST_BOUNCE] = value.listBounce
                p[K_REDUCE_MOTION] = value.reduceMotion
                p[K_HAPTICS] = value.haptics
                p[K_AUTO_REVIEW] = value.autoReview
                p[K_NOTIFY_SUSPICIOUS] = value.notifySuspicious
                p[K_NOTIFY_NOTABLE] = value.notifyNotable
                p[K_NOTIFY_CB] = value.notifyCb
                p[K_NOTIFY_DIGEST] = value.notifyDigest.wire
                p[K_NOTIFY_RATE_LIMIT] = value.notifyRateLimitMin
                p[K_DETECT_SILENT_COUNT] = value.detectSilentCount
                p[K_DETECT_SILENT_WINDOW] = value.detectSilentWindowMin
                p[K_DETECT_SILENT_NEW_SENDER] = value.detectSilentNewSender
                p[K_DETECT_SILENT_ROAMING] = value.detectSilentRoaming
                p[K_DETECT_SUPL_GRACE] = value.detectSuplGraceMin
                p[K_SEVERITY_OVERRIDES] = SettingsJson.writeSeverityOverrides(value.severityOverrides)
                p[K_OUTGOING_ENABLED] = value.outgoingEnabled
                p[K_OUTGOING_LOG_VISIBLE] = value.outgoingLogVisible
                p[K_CB_LOG] = value.cbLog
                p[K_CB_ALL_CHANNELS] = value.cbAllChannels
                p[K_APP_LOCK] = value.appLock
                p[K_LOCK_TIMEOUT] = value.lockTimeout.wire
                p[K_MASK_NUMBERS] = value.maskNumbers
                p[K_HIDE_IN_NOTIFICATIONS] = value.hideInNotifications
                p[K_BLOCK_SCREENSHOTS] = value.blockScreenshots
                p[K_RETENTION] = value.retention.wire
                p[K_DEBUG_LOGGING] = value.debugLogging
                p[K_DEBUG_LOGGING_UNTIL] = value.debugLoggingUntil
                p[K_SAFE_MODE] = value.safeMode
                p[K_ONBOARDING_DONE] = value.onboardingDone
                p[K_SHOW_DELIVERY_REPORTS] = value.showDeliveryReports
                value.lastBuildFingerprint?.let { p[K_LAST_FINGERPRINT] = it }
            }
        }

        // One branch per key with a default or a clamp; a lookup table would hide the fixed ranges.
        @Suppress("CyclomaticComplexMethod")
        private fun read(p: Preferences): Settings =
            Settings(
                mode = UiMode.from(p[K_MODE]),
                theme = ThemeMode.from(p[K_THEME]),
                dynamicColor = p[K_DYNAMIC_COLOR] ?: false,
                accent = NjAccent.from(p[K_ACCENT]),
                trueBlack = p[K_TRUE_BLACK] ?: false,
                floatingBar = p[K_FLOATING_BAR] ?: true,
                listAnimations = p[K_LIST_ANIMATIONS] ?: true,
                listBounce = p[K_LIST_BOUNCE] ?: true,
                reduceMotion = p[K_REDUCE_MOTION] ?: false,
                haptics = p[K_HAPTICS] ?: true,
                autoReview = p[K_AUTO_REVIEW] ?: true,
                notifySuspicious = p[K_NOTIFY_SUSPICIOUS] ?: true,
                notifyNotable = p[K_NOTIFY_NOTABLE] ?: false,
                notifyCb = p[K_NOTIFY_CB] ?: false,
                notifyDigest = DigestMode.from(p[K_NOTIFY_DIGEST]),
                notifyRateLimitMin = Settings.clampRateLimit(p[K_NOTIFY_RATE_LIMIT] ?: Settings.DEFAULT_RATE_LIMIT_MIN),
                detectSilentCount = Settings.clampSilentCount(p[K_DETECT_SILENT_COUNT] ?: Settings.DEFAULT_SILENT_COUNT),
                detectSilentWindowMin =
                    Settings.clampSilentWindow(
                        p[K_DETECT_SILENT_WINDOW] ?: Settings.DEFAULT_SILENT_WINDOW_MIN,
                    ),
                detectSilentNewSender = p[K_DETECT_SILENT_NEW_SENDER] ?: true,
                detectSilentRoaming = p[K_DETECT_SILENT_ROAMING] ?: true,
                detectSuplGraceMin = Settings.clampSuplGrace(p[K_DETECT_SUPL_GRACE] ?: Settings.DEFAULT_SUPL_GRACE_MIN),
                severityOverrides = SettingsJson.readSeverityOverrides(p[K_SEVERITY_OVERRIDES]),
                outgoingEnabled = p[K_OUTGOING_ENABLED] ?: true,
                outgoingLogVisible = p[K_OUTGOING_LOG_VISIBLE] ?: false,
                cbLog = p[K_CB_LOG] ?: true,
                cbAllChannels = p[K_CB_ALL_CHANNELS] ?: false,
                appLock = p[K_APP_LOCK] ?: false,
                lockTimeout = LockTimeout.from(p[K_LOCK_TIMEOUT]),
                maskNumbers = p[K_MASK_NUMBERS] ?: false,
                hideInNotifications = p[K_HIDE_IN_NOTIFICATIONS] ?: false,
                blockScreenshots = p[K_BLOCK_SCREENSHOTS] ?: false,
                retention = Retention.from(p[K_RETENTION]),
                debugLogging = p[K_DEBUG_LOGGING] ?: false,
                debugLoggingUntil = p[K_DEBUG_LOGGING_UNTIL] ?: 0,
                safeMode = p[K_SAFE_MODE] ?: false,
                onboardingDone = p[K_ONBOARDING_DONE] ?: false,
                showDeliveryReports = p[K_SHOW_DELIVERY_REPORTS] ?: true,
                lastBuildFingerprint = p[K_LAST_FINGERPRINT],
            )

        private fun diff(
            before: Settings,
            after: Settings,
        ): List<SettingsChange> {
            val out = ArrayList<SettingsChange>(8)

            fun add(
                key: String,
                old: Any?,
                new: Any?,
            ) {
                if (old != new) out += SettingsChange(key, old, new)
            }
            add("ui.mode", before.mode.wire, after.mode.wire)
            add("ui.theme", before.theme.wire, after.theme.wire)
            add("ui.dynamicColor", before.dynamicColor, after.dynamicColor)
            add("ui.accent", before.accent.wire, after.accent.wire)
            add("ui.trueBlack", before.trueBlack, after.trueBlack)
            add("ui.floatingBar", before.floatingBar, after.floatingBar)
            add("ui.listAnimations", before.listAnimations, after.listAnimations)
            add("ui.listBounce", before.listBounce, after.listBounce)
            add("ui.reduceMotion", before.reduceMotion, after.reduceMotion)
            add("ui.haptics", before.haptics, after.haptics)
            add("ui.autoReview", before.autoReview, after.autoReview)
            add("notify.suspicious", before.notifySuspicious, after.notifySuspicious)
            add("notify.notable", before.notifyNotable, after.notifyNotable)
            add("notify.cb", before.notifyCb, after.notifyCb)
            add("notify.digest", before.notifyDigest.wire, after.notifyDigest.wire)
            add("notify.rateLimitMin", before.notifyRateLimitMin, after.notifyRateLimitMin)
            add("detect.silent.count", before.detectSilentCount, after.detectSilentCount)
            add("detect.silent.windowMin", before.detectSilentWindowMin, after.detectSilentWindowMin)
            add("detect.silent.newSender", before.detectSilentNewSender, after.detectSilentNewSender)
            add("detect.silent.roaming", before.detectSilentRoaming, after.detectSilentRoaming)
            add("detect.supl.graceMin", before.detectSuplGraceMin, after.detectSuplGraceMin)
            if (before.severityOverrides != after.severityOverrides) {
                val keys = before.severityOverrides.keys + after.severityOverrides.keys
                keys.forEach { classId ->
                    add(
                        "severity.${classId.wire()}",
                        before.severityOverrides[classId]?.wire(),
                        after.severityOverrides[classId]?.wire(),
                    )
                }
            }
            add("outgoing.enabled", before.outgoingEnabled, after.outgoingEnabled)
            add("outgoing.logVisible", before.outgoingLogVisible, after.outgoingLogVisible)
            add("cb.log", before.cbLog, after.cbLog)
            add("cb.allChannels", before.cbAllChannels, after.cbAllChannels)
            add("privacy.appLock", before.appLock, after.appLock)
            add("privacy.lockTimeout", before.lockTimeout.wire, after.lockTimeout.wire)
            add("privacy.maskNumbers", before.maskNumbers, after.maskNumbers)
            add("privacy.hideInNotifications", before.hideInNotifications, after.hideInNotifications)
            add("privacy.blockScreenshots", before.blockScreenshots, after.blockScreenshots)
            add("data.retentionDays", before.retention.wire, after.retention.wire)
            add("advanced.debugLogging", before.debugLogging, after.debugLogging)
            add("advanced.safeMode", before.safeMode, after.safeMode)
            add(KEY_ONBOARDING_DONE, before.onboardingDone, after.onboardingDone)
            add("messages.showDeliveryReports", before.showDeliveryReports, after.showDeliveryReports)
            add(KEY_LAST_FINGERPRINT, before.lastBuildFingerprint, after.lastBuildFingerprint)
            return out
        }

        private companion object {
            const val READ_RETRY_MS = 2_000L

            val K_MODE = stringPreferencesKey("ui.mode")
            val K_THEME = stringPreferencesKey("ui.theme")
            val K_DYNAMIC_COLOR = booleanPreferencesKey("ui.dynamicColor")
            val K_ACCENT = stringPreferencesKey("ui.accent")
            val K_TRUE_BLACK = booleanPreferencesKey("ui.trueBlack")
            val K_FLOATING_BAR = booleanPreferencesKey("ui.floatingBar")
            val K_OLD_BLUR_EFFECTS = booleanPreferencesKey("ui.blurEffects")
            val K_LIST_ANIMATIONS = booleanPreferencesKey("ui.listAnimations")
            val K_LIST_BOUNCE = booleanPreferencesKey("ui.listBounce")
            val K_REDUCE_MOTION = booleanPreferencesKey("ui.reduceMotion")
            val K_HAPTICS = booleanPreferencesKey("ui.haptics")
            val K_AUTO_REVIEW = booleanPreferencesKey("ui.autoReview")
            val K_NOTIFY_SUSPICIOUS = booleanPreferencesKey("notify.suspicious")
            val K_NOTIFY_NOTABLE = booleanPreferencesKey("notify.notable")
            val K_OLD_NOTIFY_FIREWALL = booleanPreferencesKey("notify.firewall")
            val K_NOTIFY_CB = booleanPreferencesKey("notify.cb")
            val K_NOTIFY_DIGEST = stringPreferencesKey("notify.digest")
            val K_NOTIFY_RATE_LIMIT = intPreferencesKey("notify.rateLimitMin")
            val K_DETECT_SILENT_COUNT = intPreferencesKey("detect.silent.count")
            val K_DETECT_SILENT_WINDOW = intPreferencesKey("detect.silent.windowMin")
            val K_DETECT_SILENT_NEW_SENDER = booleanPreferencesKey("detect.silent.newSender")
            val K_DETECT_SILENT_ROAMING = booleanPreferencesKey("detect.silent.roaming")
            val K_DETECT_SUPL_GRACE = intPreferencesKey("detect.supl.graceMin")
            val K_SEVERITY_OVERRIDES = stringPreferencesKey("severity.overrides")
            val K_OUTGOING_ENABLED = booleanPreferencesKey("outgoing.enabled")
            val K_OUTGOING_LOG_VISIBLE = booleanPreferencesKey("outgoing.logVisible")
            val K_CB_LOG = booleanPreferencesKey("cb.log")
            val K_CB_ALL_CHANNELS = booleanPreferencesKey("cb.allChannels")
            val K_APP_LOCK = booleanPreferencesKey("privacy.appLock")
            val K_LOCK_TIMEOUT = stringPreferencesKey("privacy.lockTimeout")
            val K_MASK_NUMBERS = booleanPreferencesKey("privacy.maskNumbers")
            val K_HIDE_IN_NOTIFICATIONS = booleanPreferencesKey("privacy.hideInNotifications")
            val K_BLOCK_SCREENSHOTS = booleanPreferencesKey("privacy.blockScreenshots")
            val K_RETENTION = stringPreferencesKey("data.retentionDays")
            val K_DEBUG_LOGGING = booleanPreferencesKey("advanced.debugLogging")
            val K_DEBUG_LOGGING_UNTIL = longPreferencesKey("advanced.debugLoggingUntil")
            val K_SAFE_MODE = booleanPreferencesKey("advanced.safeMode")
            val K_ONBOARDING_DONE = booleanPreferencesKey("onboarding.done")
            val K_SHOW_DELIVERY_REPORTS = booleanPreferencesKey("messages.showDeliveryReports")
            const val KEY_ONBOARDING_DONE = "onboarding.done"
            const val KEY_LAST_FINGERPRINT = "build.lastFingerprint"
            val K_LAST_FINGERPRINT = stringPreferencesKey(KEY_LAST_FINGERPRINT)

            /** Keys that change with app state, not by a user action; [edit] records no audit row for them. */
            val NOT_AUDITED = setOf(KEY_ONBOARDING_DONE, KEY_LAST_FINGERPRINT)
        }
    }

/** DataStore file name; not an IPC key, so it is a local constant rather than a :contract value. */
private const val SETTINGS_STORE_FILE = "settings" // contract-ok: DataStore file name, not an IPC Bundle key

/** DataStore handle for the settings catalogue. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_STORE_FILE,
    // A corrupt file would otherwise make every later edit throw; the defaults replace it (and `read` fills them in).
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
