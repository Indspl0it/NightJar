package dev.nightjar.app.feature.firewall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.ProtectionState
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.RuleOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * state.
 *
 * The screen shows the live rule snapshot, the user's blocking switch and the protection report; it never keeps a
 * local copy of the class action matrix. A risky class action or a preset change becomes a [FirewallDialog] first, and
 * only [confirmDialog] writes it, so "Cancel" always leaves the previous action in place.
 */
@HiltViewModel
class FirewallViewModel
    @Inject
    constructor(
        private val port: FirewallPort,
    ) : ViewModel() {
        private val dialog = MutableStateFlow<FirewallDialog?>(null)
        private val saving = MutableStateFlow(false)
        private val channel = Channel<FirewallEvent>(Channel.BUFFERED)

        /** Snackbar and undo events; collected once by the screen. */
        val events = channel.receiveAsFlow()

        private val base =
            combine(
                port.rules,
                port.blockingOn,
                port.protection,
                dialog,
                saving,
            ) { config, on, report, openDialog, busy ->
                // The resolved actions, so an absent CL-UNREADABLE-SMS entry shows as Hold.
                val actions = resolvedClassActions(config)
                val rows = firewallRows(actions)
                FirewallUiState(
                    loading = report == null,
                    preset = effectivePreset(config.preset.toPresetId(), actions),
                    rows = rows,
                    userRuleCount = config.rules.count { it.origin() == RuleOrigin.USER },
                    blockingOn = on,
                    firewallAvailable = report?.firewallAvailable == true,
                    readOnly = report != null && !report.firewallAvailable,
                    reasons = reasonsOf(report),
                    dialog = openDialog,
                    saving = busy,
                )
            }

        val state: StateFlow<FirewallUiState> =
            combine(base, port.knownSenderCount) { ui, senders -> ui.copy(knownSenderCount = senders) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FirewallUiState())

        /**
         * Reads the protection state again. The screen calls it each time it is shown: the ViewModel lives as long as
         * the activity, so the state is fresh on every visit and not only on the first one.
         */
        fun onShown() {
            viewModelScope.launch { runCatching { port.refreshProtection() } }
        }

        /** "Done" asks for a class action; guard rails may turn this into a dialog. */
        fun requestClassAction(
            classId: ClassId,
            verdict: Verdict,
        ) {
            val current = state.value
            if (current.saving || current.readOnly) return
            val row = current.rows.firstOrNull { it.classId == classId } ?: return
            if (verdict !in row.allowedActions) return
            if (verdict == row.action) return
            val pending = PendingAction.ClassAction(classId, verdict)
            when {
                classId == ClassId.SIM_DD && verdict == Verdict.BLOCK -> {
                    dialog.value = FirewallDialog.GuardSimUpdate(pending)
                }

                classId in CONSUMER_CLASSES && verdict == Verdict.BLOCK -> {
                    dialog.value = FirewallDialog.GuardConsumerClass(pending, classId)
                }

                else -> {
                    apply(pending)
                }
            }
        }

        /** Replacing a custom configuration asks first. */
        fun requestPreset(preset: PresetId) {
            val current = state.value
            val unchanged = preset == current.preset || current.readOnly || current.saving
            if (preset == PresetId.CUSTOM || unchanged) return
            if (current.preset == PresetId.CUSTOM) {
                dialog.value = FirewallDialog.PresetReplace(preset)
            } else {
                apply(PendingAction.Preset(preset))
            }
        }

        /**
         * Turning blocking off asks first ([FirewallDialog.BlockingOffConfirm]); turning it on writes at once. Safe
         * mode is a separate switch in Settings.
         */
        fun requestBlocking(on: Boolean) {
            val current = state.value
            if (current.saving || current.readOnly || on == current.blockingOn) return
            if (on) {
                writeBlocking(true)
            } else {
                dialog.value = FirewallDialog.BlockingOffConfirm
            }
        }

        fun showEmergencyInfo() {
            dialog.value = FirewallDialog.EmergencyInfo
        }

        fun confirmDialog() {
            val open = dialog.value ?: return
            dialog.value = null
            when (open) {
                FirewallDialog.BlockingOffConfirm -> writeBlocking(false)
                FirewallDialog.EmergencyInfo -> Unit
                is FirewallDialog.PresetReplace -> apply(PendingAction.Preset(open.preset))
                is FirewallDialog.GuardSimUpdate -> apply(open.pending)
                is FirewallDialog.GuardConsumerClass -> apply(open.pending)
            }
        }

        fun dismissDialog() {
            if (!saving.value) dialog.value = null
        }

        private fun apply(pending: PendingAction) {
            if (saving.value) return
            saving.value = true
            viewModelScope.launch {
                try {
                    when (pending) {
                        is PendingAction.ClassAction -> {
                            port.setClassAction(pending.classId, pending.verdict)
                            channel.send(FirewallEvent.ClassActionChanged(pending.classId, pending.verdict))
                        }

                        is PendingAction.Preset -> {
                            port.applyPreset(pending.preset.toRulesPreset())
                            channel.send(FirewallEvent.PresetChanged(pending.preset))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.send(FirewallEvent.Failed)
                } finally {
                    saving.value = false
                }
            }
        }

        private fun writeBlocking(on: Boolean) {
            if (saving.value) return
            saving.value = true
            viewModelScope.launch {
                try {
                    val sent = port.setBlocking(on)
                    runCatching { port.refreshProtection() }
                    if (!sent) channel.send(FirewallEvent.BlockingNotSent)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.send(FirewallEvent.Failed)
                } finally {
                    saving.value = false
                }
            }
        }

        private fun reasonsOf(report: ProtectionReport?): List<FirewallReason> {
            if (report == null) return emptyList()
            val out = ArrayList<FirewallReason>(2)
            if (report.state == ProtectionState.SAFE_MODE) {
                out += FirewallReason.SAFE_MODE_FLAG
                return out
            }
            firewallReason(report.reason)?.let { out += it }
            if (!report.firewallAvailable && out.isEmpty()) out += FirewallReason.HOOKS_FAILED
            if (report.state == ProtectionState.PROTECTED && report.firewallAvailable && !report.firewallEnabled) {
                out += FirewallReason.NOT_ENABLED
            }
            return out.distinct()
        }

        private companion object {
            /**
             * Blocking these breaks voicemail, so a dialog warns first. CL-VVM and CL-RCS-PROV cannot be
             * blocked, so only CL-MWI is left.
             */
            val CONSUMER_CLASSES = setOf(ClassId.MWI)
        }
    }
