package dev.nightjar.app.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import dagger.hilt.android.AndroidEntryPoint
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.data.model.MessageDetail
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.domain.notify.NotificationAction
import dev.nightjar.app.feature.classify.QuarantineReleaser
import dev.nightjar.app.feature.classify.ReleaseOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the notification actions that must not open the app: Allow and Block on a
 * message that NightJar holds.
 *
 * `goAsync()` with a short wakelock is required: the process may be killed as soon as `onReceive` returns, and these
 * actions write to the database. The receiver is not exported, so no other app can drive these state changes. The
 * intents are explicit, so the receiver gets them without an intent filter.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var messages: MessageRepository

    @Inject lateinit var notifier: Notifier

    @Inject lateinit var releasers: Set<@JvmSuppressWildcards QuarantineReleaser>

    @Inject lateinit var log: ALog

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = actionOf(intent.action) ?: return
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L).takeIf { it > 0 }
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val pending = goAsync()
        val wakeLock =
            context.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
        wakeLock?.acquire(WAKE_TIMEOUT_MS)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A database or binder failure must not stop the process: the action is lost, the app keeps running.
                runCatching { handle(action, messageId, notificationId) }
                    .onFailure { log.w(TAG, "notification action failed action=${action.name}", it) }
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                pending.finish()
            }
        }
    }

    private suspend fun handle(
        action: NotificationAction,
        messageId: Long?,
        notificationId: Int,
    ) {
        val detail = messageId?.let { messages.find(it) } ?: return
        when (action) {
            NotificationAction.ALLOW -> release(detail, notificationId)
            NotificationAction.BLOCK -> block(detail, notificationId)
            NotificationAction.OPEN -> Unit
        }
    }

    /** AC3: the payload re-injects the message; a failure explains itself in a follow-up. */
    private suspend fun release(
        detail: MessageDetail,
        notificationId: Int,
    ) {
        val releaser = releasers.firstOrNull()
        if (releaser == null) {
            notifier.post(notifier.releaseFailedNotice(null, detail))
            return
        }
        when (val outcome = releaser.release(detail.id)) {
            // The gateway records the RELEASE audit row itself. Remove the notification that carried the action (a
            // burst notification has its own id).
            is ReleaseOutcome.Released -> {
                notifier.cancel(notificationId)
            }

            is ReleaseOutcome.Failed -> {
                notifier.post(notifier.releaseFailedNotice(outcome.reason, detail))
            }
        }
    }

    /**
     * U8: Block keeps a held message out for good. The repository blocks it only while it is on hold (fate Blocked, so
     * it counts as blocked on Home and in the digest), writes the BLOCK_HELD audit row and marks it as reviewed.
     */
    private suspend fun block(
        detail: MessageDetail,
        notificationId: Int,
    ) {
        messages.blockHeld(detail.id)
        notifier.cancel(notificationId)
    }

    companion object {
        const val ACTION_RELEASE = "dev.nightjar.app.action.RELEASE"
        const val ACTION_BLOCK = "dev.nightjar.app.action.BLOCK_HELD"

        const val EXTRA_MESSAGE_ID = "dev.nightjar.app.extra.MESSAGE_ID"
        const val EXTRA_NOTIFICATION_ID = "dev.nightjar.app.extra.NOTIFICATION_ID"

        /** An action that opens the app has no receiver work; the receiver ignores this value. */
        private const val ACTION_NONE = "dev.nightjar.app.action.NONE"
        private const val TAG = "notifAction"

        private const val WAKE_TAG = "nightjar:notification"
        private const val WAKE_TIMEOUT_MS = 10_000L

        /** Builds the action intent. Explicit and immutable: only the system can deliver it. */
        fun intent(
            context: Context,
            action: NotificationAction,
            notificationId: Int,
            messageId: Long?,
        ): PendingIntent {
            val intent =
                Intent(context, NotificationActionReceiver::class.java).apply {
                    setAction(actionOf(action))
                    putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                    if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId)
                }
            return PendingIntent.getBroadcast(
                context,
                notificationId * ACTION_CODE_STRIDE + action.ordinal,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun actionOf(action: NotificationAction): String =
            when (action) {
                NotificationAction.ALLOW -> ACTION_RELEASE

                NotificationAction.BLOCK -> ACTION_BLOCK

                // Open goes to the activity and never reaches this receiver; if it does, it does nothing.
                NotificationAction.OPEN -> ACTION_NONE
            }

        private fun actionOf(wire: String?): NotificationAction? =
            when (wire) {
                ACTION_RELEASE -> NotificationAction.ALLOW
                ACTION_BLOCK -> NotificationAction.BLOCK
                else -> null
            }

        private const val ACTION_CODE_STRIDE = 8
    }
}
