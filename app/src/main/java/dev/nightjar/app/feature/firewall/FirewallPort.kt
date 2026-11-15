package dev.nightjar.app.feature.firewall

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.nightjar.app.core.ipc.ControlResult
import dev.nightjar.app.core.ipc.FirewallSwitch
import dev.nightjar.app.data.repository.KnownOriginatorRepository
import dev.nightjar.app.data.repository.RulesRepository
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.app.feature.protection.ProtectionMonitor
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Preset
import dev.nightjar.rules.RulesConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The narrow surface Protection needs.
 *
 * A port rather than the repositories themselves: the screen only reads the rule snapshot, the blocking switch and the
 * protection report and performs three mutations, so the ViewModel stays testable on the JVM with a small fake and
 * cannot reach the database or root by accident.
 */
interface FirewallPort {
    val rules: Flow<RulesConfig>

    /** The user's blocking switch. Blocking is on by default on every build. */
    val blockingOn: Flow<Boolean>

    /** How many known senders the user has (the count on the Known senders row). */
    val knownSenderCount: Flow<Int> get() = flowOf(0)

    /** Null until the first [refreshProtection] has completed. */
    val protection: StateFlow<ProtectionReport?>

    suspend fun setClassAction(
        classId: ClassId,
        verdict: Verdict,
    )

    suspend fun applyPreset(preset: Preset)

    /**
     * Stores the choice (the switch audits it) and sends it to the payload. True when the payload has it now or does not
     * run (the next hello carries it). False when the binder died or the payload rejected the call: the choice is stored
     * and the next hello carries it, so the screen shows a note, not an error.
     */
    suspend fun setBlocking(on: Boolean): Boolean

    suspend fun refreshProtection()
}

@Singleton
class RepositoryFirewallPort
    @Inject
    constructor(
        private val rulesRepository: RulesRepository,
        private val knownSenders: KnownOriginatorRepository,
        private val monitor: ProtectionMonitor,
        private val firewallSwitch: FirewallSwitch,
    ) : FirewallPort {
        override val rules: Flow<RulesConfig> = rulesRepository.observe()

        override val blockingOn: Flow<Boolean> = firewallSwitch.state

        override val knownSenderCount: Flow<Int> = knownSenders.observeAll().map { it.size }

        override val protection: StateFlow<ProtectionReport?> get() = monitor.report

        override suspend fun setClassAction(
            classId: ClassId,
            verdict: Verdict,
        ) {
            rulesRepository.setClassAction(classId, verdict)
        }

        override suspend fun applyPreset(preset: Preset) {
            rulesRepository.applyPreset(preset)
        }

        override suspend fun setBlocking(on: Boolean): Boolean =
            when (firewallSwitch.set(on)) {
                is ControlResult.Ok, ControlResult.NoSession -> true
                ControlResult.Dead, is ControlResult.Failed -> false
            }

        override suspend fun refreshProtection() {
            monitor.refresh()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
object FirewallBindings {
    @Provides
    @Singleton
    fun firewallPort(impl: RepositoryFirewallPort): FirewallPort = impl
}
