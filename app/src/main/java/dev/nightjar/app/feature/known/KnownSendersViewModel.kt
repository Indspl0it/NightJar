package dev.nightjar.app.feature.known

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.data.model.KnownOriginator
import dev.nightjar.app.data.model.KnownOriginatorKind
import dev.nightjar.app.data.model.Mute
import dev.nightjar.app.data.model.TrustedPackage
import dev.nightjar.app.data.repository.KnownOriginatorRepository
import dev.nightjar.app.data.repository.MuteRepository
import dev.nightjar.app.data.repository.TrustedPackageRepository
import dev.nightjar.app.domain.rules.KnownOriginatorPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class KnownTab {
    SENDERS,
    APPS,
    MUTES,
}

/** Typed errors of the "add known sender" form, mirroring [KnownOriginatorPolicy]. */
enum class KnownValidation {
    EMPTY,
    TOO_LONG,
    NOT_A_NUMBER,
    DUPLICATE,
}

/** What a delete removed, so the snackbar and its Undo can say the right thing. */
enum class KnownEntryKind {
    SENDER,
    APP,
    MUTE,
}

/** The "add known sender" / edit form. [id] is set when editing an existing entry. */
data class KnownForm(
    val id: Long? = null,
    val value: String = "",
    val kind: KnownOriginatorKind = KnownOriginatorKind.EXACT,
    /** One of [KnownOriginatorPolicy.LABELS], blank for no label; an older free-text label is kept as it is. */
    val label: String = "",
)

data class KnownSendersUiState(
    val loading: Boolean = true,
    val tab: KnownTab = KnownTab.SENDERS,
    val senders: List<KnownOriginator> = emptyList(),
    val apps: List<TrustedPackage> = emptyList(),
    val mutes: List<Mute> = emptyList(),
    val form: KnownForm? = null,
    val validation: KnownValidation? = null,
    val busy: Boolean = false,
    /** The trusted apps picker covers the screen. */
    val pickerOpen: Boolean = false,
)

sealed interface KnownSendersEvent {
    data object Saved : KnownSendersEvent

    /** The trusted apps picker saved a change. */
    data object TrustSaved : KnownSendersEvent

    /** An entry was removed. [token] goes back to [KnownSendersViewModel.undoDelete], so Undo restores this entry. */
    data class Deleted(
        val token: Long,
        val kind: KnownEntryKind,
    ) : KnownSendersEvent

    data class Restored(
        val kind: KnownEntryKind,
    ) : KnownSendersEvent

    /** A write failed. The screen shows a fixed text: an exception message is technical and not translated. */
    data object Failed : KnownSendersEvent
}

/**
 * state.
 *
 * Known senders are permanent; editing is remove-then-add because the repository has no in-place update, and the
 * operation is audited as a removal plus an addition. Deleting a sender, a trusted app or a mute is undoable for the
 * snackbar's lifetime; each delete has its own restore point, so a quick second delete cannot make the first Undo
 * restore the wrong entry.
 */
@HiltViewModel
class KnownSendersViewModel
    @Inject
    constructor(
        private val senders: KnownOriginatorRepository,
        private val apps: TrustedPackageRepository,
        private val mutes: MuteRepository,
    ) : ViewModel() {
        private data class Overlay(
            val tab: KnownTab = KnownTab.SENDERS,
            val form: KnownForm? = null,
            val validation: KnownValidation? = null,
            val busy: Boolean = false,
            val pickerOpen: Boolean = false,
        )

        private val overlay = MutableStateFlow(Overlay())
        private val channel = Channel<KnownSendersEvent>(Channel.BUFFERED)
        val events = channel.receiveAsFlow()

        /** Restore points by token, newest last. */
        private val deleted = LinkedHashMap<Long, Deleted>()
        private var nextToken = 0L

        val state: StateFlow<KnownSendersUiState> =
            combine(
                senders.observeAll(),
                apps.observeAll(),
                mutes.observeActive(),
                overlay,
            ) { senderRows, appRows, muteRows, ui ->
                KnownSendersUiState(
                    loading = false,
                    tab = ui.tab,
                    senders = senderRows,
                    apps = appRows,
                    mutes = muteRows,
                    form = ui.form,
                    validation = ui.validation,
                    busy = ui.busy,
                    pickerOpen = ui.pickerOpen,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), KnownSendersUiState())

        fun selectTab(tab: KnownTab) {
            overlay.update { it.copy(tab = tab) }
        }

        /** Opens the trusted apps picker. A rotation keeps it open, because the state lives here. */
        fun openPicker() {
            overlay.update { it.copy(pickerOpen = true, tab = KnownTab.APPS) }
        }

        /** Closes the trusted apps picker. [saved] is true when the picker wrote a change. The screen then tells the user. */
        fun closePicker(saved: Boolean) {
            overlay.update { it.copy(pickerOpen = false) }
            if (saved) channel.trySend(KnownSendersEvent.TrustSaved)
        }

        fun openAdd() {
            overlay.update { it.copy(form = KnownForm(), validation = null) }
        }

        fun openEdit(sender: KnownOriginator) {
            overlay.update {
                it.copy(
                    form =
                        KnownForm(
                            id = sender.id,
                            value = sender.value,
                            kind = sender.kind,
                            label = sender.label.orEmpty(),
                        ),
                    validation = null,
                )
            }
        }

        fun updateForm(form: KnownForm) {
            overlay.update { it.copy(form = form, validation = null) }
        }

        fun closeForm() {
            overlay.update { it.copy(form = null, validation = null) }
        }

        /** Add / Save. Returns nothing; the dialog closes when the row was written. */
        fun saveForm() {
            val current = overlay.value
            val form = current.form ?: return
            val problem = validationOf(form.value)
            if (problem != null) {
                overlay.update { it.copy(validation = problem) }
                return
            }
            if (current.busy) return
            overlay.update { it.copy(busy = true) }
            viewModelScope.launch {
                try {
                    save(form)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.send(KnownSendersEvent.Failed)
                } finally {
                    overlay.update { it.copy(busy = false) }
                }
            }
        }

        private suspend fun save(form: KnownForm) {
            val existing = form.id
            var previous: KnownOriginator? = null
            if (existing != null) {
                val rows = senders.all()
                previous = rows.firstOrNull { it.id == existing }
                // The repository has no update, so replacing must not delete the old row when the new value already
                // exists under another id.
                val duplicate =
                    rows.any { row ->
                        row.id != existing &&
                            row.kind == form.kind &&
                            row.value.equals(form.value.trim(), ignoreCase = true)
                    }
                if (duplicate) {
                    overlay.update { it.copy(validation = KnownValidation.DUPLICATE) }
                    return
                }
                senders.remove(existing)
            }
            val stored =
                try {
                    senders.add(
                        value = form.value.trim(),
                        kind = form.kind,
                        label = form.label.trim().ifEmpty { null },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (failure: Exception) {
                    restore(previous)
                    throw failure
                }
            if (stored == null) {
                // An add can still fail (e.g. a concurrent writer took the value): put the old entry back so a
                // rejected edit never loses the sender it was editing (.1).
                restore(previous)
                overlay.update { it.copy(validation = KnownValidation.DUPLICATE) }
            } else {
                overlay.update { it.copy(form = null, validation = null) }
                channel.send(KnownSendersEvent.Saved)
            }
        }

        fun deleteSender(sender: KnownOriginator) {
            launchDelete(KnownEntryKind.SENDER, Deleted.Sender(sender)) { senders.remove(sender.id) }
        }

        fun removeTrustedApp(app: TrustedPackage) {
            launchDelete(KnownEntryKind.APP, Deleted.App(app)) { apps.remove(app.packageName) }
        }

        fun clearMute(mute: Mute) {
            launchDelete(KnownEntryKind.MUTE, Deleted.MuteEntry(mute)) { mutes.clear(mute.scope, mute.value) }
        }

        /** Undo restores the deletion of [token] (a new id for senders and apps; the mute keeps its expiry). */
        fun undoDelete(token: Long) {
            val item = deleted.remove(token) ?: return
            viewModelScope.launch {
                try {
                    val kind =
                        when (item) {
                            is Deleted.Sender -> {
                                senders.add(item.sender.value, item.sender.kind, item.sender.label)
                                KnownEntryKind.SENDER
                            }

                            is Deleted.App -> {
                                apps.add(item.app.packageName, item.app.label)
                                KnownEntryKind.APP
                            }

                            is Deleted.MuteEntry -> {
                                mutes.mute(item.mute.scope, item.mute.value, item.mute.until)
                                KnownEntryKind.MUTE
                            }
                        }
                    channel.send(KnownSendersEvent.Restored(kind))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.send(KnownSendersEvent.Failed)
                }
            }
        }

        /**
         * Runs one delete. The restore point is recorded only once the delete is applied: a delete dropped because
         * another one is running, or one that fails, must not leave an Undo for a row that is still there.
         */
        private fun launchDelete(
            kind: KnownEntryKind,
            restorePoint: Deleted,
            block: suspend () -> Unit,
        ) {
            if (overlay.value.busy) return
            overlay.update { it.copy(busy = true) }
            viewModelScope.launch {
                try {
                    block()
                    val token = nextToken++
                    deleted[token] = restorePoint
                    while (deleted.size > MAX_RESTORE_POINTS) deleted.remove(deleted.keys.first())
                    channel.send(KnownSendersEvent.Deleted(token, kind))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.send(KnownSendersEvent.Failed)
                } finally {
                    overlay.update { it.copy(busy = false) }
                }
            }
        }

        /**
         * Re-creates the row an edit removed when its replacement could not be written (.1). When that fails too,
         * the user is told: the edit lost the entry.
         */
        private suspend fun restore(previous: KnownOriginator?) {
            if (previous == null) return
            try {
                senders.add(previous.value, previous.kind, previous.label)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                channel.send(KnownSendersEvent.Failed)
            }
        }

        private fun validationOf(value: String): KnownValidation? =
            when (KnownOriginatorPolicy.check(value)) {
                null -> null
                KnownOriginatorPolicy.Error.EMPTY -> KnownValidation.EMPTY
                KnownOriginatorPolicy.Error.TOO_LONG -> KnownValidation.TOO_LONG
                KnownOriginatorPolicy.Error.NOT_A_NUMBER -> KnownValidation.NOT_A_NUMBER
            }

        private sealed interface Deleted {
            data class Sender(
                val sender: KnownOriginator,
            ) : Deleted

            data class App(
                val app: TrustedPackage,
            ) : Deleted

            data class MuteEntry(
                val mute: Mute,
            ) : Deleted
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L

            /** Undo lives as long as its snackbar; a few restore points cover rapid deletes. */
            const val MAX_RESTORE_POINTS = 8
        }
    }
