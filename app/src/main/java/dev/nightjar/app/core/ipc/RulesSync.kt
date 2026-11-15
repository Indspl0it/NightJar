package dev.nightjar.app.core.ipc

import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.core.settings.OutgoingBlockStore
import dev.nightjar.app.core.settings.Settings
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.repository.KnownOriginatorRepository
import dev.nightjar.app.data.repository.RulesRepository
import dev.nightjar.app.domain.protection.ModuleSettingsFactory
import dev.nightjar.app.domain.rules.RulesSnapshot
import dev.nightjar.rules.RulesConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the payload in step with the rules database.
 *
 * The Xposed payload reads no file: it gets the snapshot in the `hello` reply and through `updateRules`. A rule change
 * therefore survives a phone-process restart because the next `hello` carries it (AC1). Before the first
 * unlock the app cannot read the rules, so the payload runs without rules (monitor only) until the unlock.
 * Pushes are coalesced by [distinctUntilChanged] on the serialized document, so a burst of edits produces one push.
 */
@Singleton
class RulesSync
    @Inject
    constructor(
        private val rules: RulesRepository,
        private val knownOriginators: KnownOriginatorRepository,
        private val connection: PayloadConnection,
        private val log: ALog,
    ) {
        /** Starts observing. Idempotent per process: [IpcSyncCoordinator] calls this once. */
        fun launch(scope: CoroutineScope) {
            combine(rules.observe(), knownOriginators.observeAll()) { config, known ->
                RulesSnapshot.build(
                    base = config,
                    knownOriginators = SeverityConfigFactoryKnown.map(known),
                    revision = config.revision(),
                )
            }.distinctUntilChanged { a, b -> serialize(a) == serialize(b) }
                .onEach { snapshot -> publish(snapshot) }
                .launchIn(scope)
        }

        /** Pushes one snapshot to the payload. Without a session the next `hello` carries it. */
        fun publish(snapshot: RulesConfig) {
            val json = serialize(snapshot)
            when (val result = connection.updateRules(json)) {
                is ControlResult.Ok -> Unit

                // Applied at the next handshake: the hello reply carries the current snapshot.
                ControlResult.NoSession, ControlResult.Dead -> Unit

                is ControlResult.Failed -> log.w(TAG, "updateRules failed: ${result.message}")
            }
        }

        private fun serialize(config: RulesConfig): String =
            runCatching {
                dev.nightjar.rules.RulesJson
                    .serialize(config)
            }.getOrElse { "" }

        private companion object {
            const val TAG = "rulesSync"
        }
    }

/** Maps the stored known-originator rows to the domain matcher input, without touching the data layer. */
private object SeverityConfigFactoryKnown {
    fun map(rows: List<dev.nightjar.app.data.model.KnownOriginator>): List<dev.nightjar.app.domain.known.KnownOriginator> =
        rows.map {
            dev.nightjar.app.domain.known.KnownOriginator(
                value = it.value,
                kind =
                    when (it.kind) {
                        dev.nightjar.app.data.model.KnownOriginatorKind.EXACT -> dev.nightjar.app.domain.known.KnownOriginator.Kind.EXACT
                        dev.nightjar.app.data.model.KnownOriginatorKind.PREFIX -> dev.nightjar.app.domain.known.KnownOriginator.Kind.PREFIX
                    },
                label = it.label,
                addedAtWallMs = it.addedAt,
            )
        }
}

/**
 * Pushes the payload settings document to the payload (`SettingsSync`). No file is written: the payload
 * reads none, and the next `hello` carries the current document.
 *
 * Only keys that the payload mirrors trigger a push, so changing the theme does not wake the phone process.
 * The blocked sender packages (`outgoing.blocked`) are mirrored too: a change in [OutgoingBlockStore] pushes the
 * document. The user's firewall choice is not in the document: it reaches the payload as ENABLE_FIREWALL
 * ([FirewallSwitch]) and in the hello reply.
 */
@Singleton
class SettingsSync
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val connection: PayloadConnection,
        private val log: ALog,
        private val moduleSettings: ModuleSettingsFactory,
        private val outgoingBlocks: OutgoingBlockStore,
    ) {
        fun launch(scope: CoroutineScope) {
            scope.launch {
                // `settings.settings` is the default object until DataStore has answered. Publishing that would write
                // a default `settings.json` (safe mode off) over the user's before the real one follows.
                settings.loaded.first { it }
                combine(settings.settings, outgoingBlocks.observe()) { current, blocked -> current to blocked }
                    .distinctUntilChanged { a, b -> mirrored(a.first, a.second) == mirrored(b.first, b.second) }
                    .collect { (current, _) -> publish(current) }
            }
        }

        fun publish(current: Settings) {
            val json = json(current)
            when (val result = connection.updateSettings(json)) {
                is ControlResult.Ok -> Unit
                ControlResult.NoSession, ControlResult.Dead -> Unit
                is ControlResult.Failed -> log.w(TAG, "updateSettings failed: ${result.message}")
            }
        }

        /** The payload-relevant projection; also the value compared by [launch] to decide whether to push. */
        fun mirrored(
            current: Settings,
            blocked: Set<String>,
        ): String =
            listOf(
                current.safeMode,
                current.outgoingEnabled,
                current.outgoingLogVisible,
                current.cbLog,
                current.cbAllChannels,
                current.debugLoggingUntil,
                current.detectSuplGraceMin,
                blocked.sorted().joinToString(","),
            ).joinToString("|")

        /** Body, complete with `appPackage`/`appCertSha256` and `outgoing.blocked`. */
        fun json(current: Settings): String = moduleSettings.build(current)

        private companion object {
            const val TAG = "settingsSync"
        }
    }
