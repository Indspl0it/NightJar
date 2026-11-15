package dev.nightjar.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.core.settings.OutgoingBlocking
import dev.nightjar.app.core.settings.SendingApp
import dev.nightjar.app.feature.classify.DefaultSmsApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** "Apps that send SMS". */
data class SendingAppsUiState(
    val loading: Boolean = true,
    /** The apps that sent SMS, newest first, then the blocked apps with no stored SMS ([OutgoingBlocking.apps]). */
    val apps: List<SendingApp> = emptyList(),
    /** The package whose "Block sending" confirm is open, or null. */
    val confirmBlock: String? = null,
    /** The package of [confirmBlock] is the default SMS app, so the confirm warns that no SMS can go out from it. */
    val confirmDefaultSmsApp: Boolean = false,
)

/** An open "Block sending" confirm. */
private data class BlockConfirm(
    val packageName: String,
    val defaultSmsApp: Boolean,
)

/** One snackbar each. A failure never carries exception text. */
sealed interface SendingAppsEvent {
    data object SaveFailed : SendingAppsEvent
}

/**
 * The list follows [OutgoingBlocking.apps]. Turning a switch on opens a confirm and blocks only after
 * [confirmBlock]. Turning it off allows sending again at once, because that removes a restriction. The store writes the
 * audit record and pushes the settings document, so this class only calls [OutgoingBlocking.setSendBlocked].
 *
 * A blocked app cannot send any SMS, so the confirm warns when the app is the default SMS app
 * ([DefaultSmsApp.packageName], read when the confirm opens because the user can change the default app at any time).
 */
@HiltViewModel
class SendingAppsViewModel internal constructor(
    apps: Flow<List<SendingApp>>,
    private val setSendBlocked: suspend (packageName: String, blocked: Boolean) -> Unit,
    private val defaultSmsPackage: suspend () -> String?,
) : ViewModel() {
    @Inject
    constructor(
        blocking: OutgoingBlocking,
        defaultSmsApp: DefaultSmsApp,
    ) : this(
        apps = blocking.apps,
        setSendBlocked = blocking::setSendBlocked,
        defaultSmsPackage = { withContext(Dispatchers.IO) { defaultSmsApp.packageName() } },
    )

    private val confirm = MutableStateFlow<BlockConfirm?>(null)
    private val channel = Channel<SendingAppsEvent>(Channel.BUFFERED)

    val events = channel.receiveAsFlow()

    val state: StateFlow<SendingAppsUiState> =
        // A query that fails shows the empty list instead of a screen that loads for ever.
        combine(apps.catch { emit(emptyList()) }, confirm) { list, pending ->
            SendingAppsUiState(
                loading = false,
                apps = list,
                confirmBlock = pending?.packageName,
                confirmDefaultSmsApp = pending?.defaultSmsApp == true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SendingAppsUiState())

    /** The "Block sending" switch of [packageName]. */
    fun onSwitch(
        packageName: String,
        block: Boolean,
    ) {
        if (block) openConfirm(packageName) else write(packageName, blocked = false)
    }

    /** The confirm "Block". */
    fun confirmBlock() {
        val packageName = confirm.value?.packageName ?: return
        confirm.value = null
        write(packageName, blocked = true)
    }

    fun dismissBlock() {
        confirm.value = null
    }

    private fun openConfirm(packageName: String) {
        viewModelScope.launch {
            val default =
                try {
                    defaultSmsPackage()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No warning is better than no confirm: the user still confirms the block.
                    null
                }
            confirm.value = BlockConfirm(packageName, defaultSmsApp = default == packageName)
        }
    }

    private fun write(
        packageName: String,
        blocked: Boolean,
    ) {
        viewModelScope.launch {
            try {
                setSendBlocked(packageName, blocked)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                channel.send(SendingAppsEvent.SaveFailed)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
