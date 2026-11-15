package dev.nightjar.app.core.ipc

import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.feature.classify.QuarantineReleaser
import dev.nightjar.app.feature.classify.ReleaseOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App side of quarantine release.
 *
 * Loads the stored PDUs, hands them to the payload through `IPayloadControl.release`, and maps the release result to
 * the copy of the release snackbars. Contributed with `@IntoSet` so the Release action finds a real releaser where the
 * empty `@Multibinds` set used to be. Multipart messages are sent in part order, which is what `injectSmsPdu` expects.
 */
@Singleton
class ReleaseGateway
    @Inject
    constructor(
        private val messages: MessageRepository,
        private val connection: PayloadConnection,
        private val audit: AuditLog,
    ) : QuarantineReleaser {
        override suspend fun release(messageId: Long): ReleaseOutcome {
            val detail = messages.find(messageId) ?: return ReleaseOutcome.Failed("RELEASE_NOT_FOUND")
            if (!ClassRegistry.isReleasable(detail.classId)) return ReleaseOutcome.Failed("RELEASE_NOT_RELEASABLE")
            if (detail.pdus.isEmpty()) return ReleaseOutcome.Failed("RELEASE_NO_PDU")
            val format = detail.pdus.first().format
            val pdus = detail.pdus.sortedBy { it.partNo }.map { it.bytes }
            val result = withContext(Dispatchers.IO) { connection.release(detail.eventId, pdus, format, detail.subId) }
            return when (result) {
                is ControlResult.Ok -> {
                    when (val released = result.value) {
                        ReleaseResult.Released -> {
                            audit.record(AuditAction.RELEASE, AuditActor.USER, target = detail.eventId)
                            ReleaseOutcome.Released
                        }

                        is ReleaseResult.Failed -> {
                            ReleaseOutcome.Failed(released.reason)
                        }
                    }
                }

                ControlResult.NoSession -> {
                    ReleaseOutcome.Failed("RELEASE_NOT_ACTIVE")
                }

                ControlResult.Dead -> {
                    ReleaseOutcome.Failed("RELEASE_NOT_ACTIVE")
                }

                is ControlResult.Failed -> {
                    ReleaseOutcome.Failed("RELEASE_GENERIC_FAILURE")
                }
            }
        }
    }

/**
 * Starts the two syncs once per process and sends the stored firewall choice once.
 *
 * Started from [EventProvider] on the first call after the unlock and from `NightjarApp` once the user is unlocked:
 * the payload can stay quiet for a long time after an app process restart, and edits must still reach it. A hello before
 * the unlock cannot read the firewall choice, so the payload runs with its default ON until [FirewallSwitch] sends it.
 */
@Singleton
class IpcSyncCoordinator
    @Inject
    constructor(
        private val rulesSync: RulesSync,
        private val settingsSync: SettingsSync,
        private val firewallSwitch: FirewallSwitch,
    ) {
        private val started =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        fun start() {
            if (!started.compareAndSet(false, true)) return
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            rulesSync.launch(scope)
            settingsSync.launch(scope)
            scope.launch { firewallSwitch.pushStoredChoice() }
        }
    }
