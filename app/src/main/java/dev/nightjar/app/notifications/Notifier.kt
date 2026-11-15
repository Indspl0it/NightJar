package dev.nightjar.app.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.data.model.MessageDetail
import dev.nightjar.app.domain.notify.AlertKind
import dev.nightjar.app.domain.notify.AlertPlan
import dev.nightjar.app.domain.notify.ChannelId
import dev.nightjar.app.domain.notify.DeepLinks
import dev.nightjar.app.domain.notify.NotificationAction
import dev.nightjar.app.domain.notify.NotificationIds
import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.app.domain.text.ReleaseFailureText
import dev.nightjar.app.domain.text.TextKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts [AlertPlan]s (T03, T04).
 *
 * Every `PendingIntent` is immutable and carries an explicit target: Android 12+ forbids broadcast trampolines, so
 * "Open" goes straight to the activity, and the actions that must work without opening the UI (Allow, Block)
 * go to [NotificationActionReceiver].
 */
@Singleton
class Notifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val explainer: Explainer,
        private val channels: NotificationChannels,
    ) {
        /**
         * Posts or updates one notification. Returns false when notifications are not allowed.
         * `areNotificationsEnabled()` (via NotificationChannels) is false while POST_NOTIFICATIONS is denied.
         */
        @SuppressLint("MissingPermission")
        fun post(plan: AlertPlan): Boolean {
            if (!channels.notificationsAllowed()) return false
            if (!channels.channelEnabled(plan.channel)) return false
            NotificationManagerCompat.from(context).notify(plan.notificationId, build(plan))
            return true
        }

        fun cancel(notificationId: Int) = NotificationManagerCompat.from(context).cancel(notificationId)

        /**
         * AC3: a failed release posts a follow-up notification instead of silently doing nothing.
         * [reason] is the `RELEASE_*` code of the failure, or null when it is not known. the text is the
         * STE sentence for that code (`ReleaseFailureText`), never the raw code, and never message content.
         */
        fun releaseFailedNotice(
            reason: String?,
            message: MessageDetail,
        ): AlertPlan =
            AlertPlan(
                // It replaces the notification of the message that could not be released, not the firewall summary.
                kind = AlertKind.MESSAGE,
                channel = ChannelId.FIREWALL,
                notificationId = NotificationIds.message(message.id),
                title = message.title,
                text = explainer.render(ReleaseFailureText.keyOf(reason)),
                bigText = null,
                publicTitle = explainer.render(TextKey.NOTIF_PUBLIC_ACTIVITY),
                publicText = explainer.render(TextKey.NOTIF_PUBLIC_ACTIVITY),
                groupKey = null,
                alerting = true,
                onlyAlertOnce = false,
                actions = listOf(NotificationAction.OPEN),
                deepLink = DeepLinks.message(message.id),
                messageId = message.id,
            )

        /** Removes a status notification once the state it described has cleared. */
        fun cancelStatus(kind: NotificationIds.StatusKind) = cancel(NotificationIds.status(kind))

        fun build(plan: AlertPlan): Notification {
            val builder =
                NotificationCompat
                    .Builder(context, plan.channel.channelId)
                    .setSmallIcon(dev.nightjar.app.R.drawable.ic_notification)
                    .setContentTitle(plan.title)
                    .setContentText(plan.text)
                    .setSubText(plan.subText)
                    .setStyle(styleFor(plan))
                    .setPublicVersion(publicVersion(plan))
                    .setAutoCancel(plan.kind != AlertKind.PROTECTION_STATUS)
                    .setOnlyAlertOnce(plan.onlyAlertOnce)
                    .setSilent(!plan.alerting)
                    .setCategory(
                        if (plan.kind ==
                            AlertKind.PROTECTION_STATUS
                        ) {
                            NotificationCompat.CATEGORY_STATUS
                        } else {
                            NotificationCompat.CATEGORY_MESSAGE
                        },
                    ).setPriority(priorityOf(plan.channel))
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

            plan.deepLink?.let { builder.setContentIntent(activityIntent(it, plan.notificationId)) }
            // U7: never a group summary. Android makes its own summary for a large group and removes it with the last child.
            plan.groupKey?.let { builder.setGroup(it) }
            plan.timeoutMs?.let { builder.setTimeoutAfter(it) }
            for (action in plan.actions) builder.addAction(actionFor(plan, action))
            return builder.build()
        }

        private fun styleFor(plan: AlertPlan): NotificationCompat.Style =
            if (plan.inboxLines.isNotEmpty()) {
                NotificationCompat.InboxStyle().setBigContentTitle(plan.title).also { style ->
                    plan.inboxLines.forEach(style::addLine)
                }
            } else {
                NotificationCompat.BigTextStyle().bigText(plan.bigText ?: plan.text)
            }

        /**
         * The lock-screen version carries no sender, class or text, only the fixed public sentence. It is a
         * separate notification because Android shows it instead of the real one on a secure lock screen.
         */
        private fun publicVersion(plan: AlertPlan): Notification =
            NotificationCompat
                .Builder(context, plan.channel.channelId)
                .setSmallIcon(dev.nightjar.app.R.drawable.ic_notification)
                .setContentTitle(plan.publicTitle)
                .setContentText(plan.publicText)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

        private fun actionFor(
            plan: AlertPlan,
            action: NotificationAction,
        ): NotificationCompat.Action {
            val label = explainer.render(action.labelKey)
            val intent =
                if (action.opensUi) {
                    val uri = plan.deepLink ?: DeepLinks.ALL_MESSAGES
                    activityIntent(uri, plan.notificationId * ACTION_ID_MULTIPLIER + action.ordinal)
                } else {
                    NotificationActionReceiver.intent(context, action, plan.notificationId, plan.messageId)
                }
            return NotificationCompat.Action.Builder(0, label, intent).build()
        }

        private fun activityIntent(
            deepLink: String,
            requestCode: Int,
        ): PendingIntent {
            val intent =
                Intent(Intent.ACTION_VIEW, deepLink.toUri()).apply {
                    setPackage(context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun priorityOf(channel: ChannelId): Int =
            when (channel) {
                ChannelId.SUSPICIOUS -> NotificationCompat.PRIORITY_HIGH
                ChannelId.HIDDEN, ChannelId.STATUS -> NotificationCompat.PRIORITY_DEFAULT
                ChannelId.FIREWALL, ChannelId.BROADCAST -> NotificationCompat.PRIORITY_LOW
                ChannelId.DIGEST -> NotificationCompat.PRIORITY_MIN
            }

        private companion object {
            /** Keeps activity request codes apart from notification ids. */
            const val ACTION_ID_MULTIPLIER = 8
        }
    }
