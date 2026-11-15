package dev.nightjar.app.notifications

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.domain.notify.ChannelId
import dev.nightjar.app.domain.notify.Importance
import dev.nightjar.app.domain.text.Explainer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the six channels.
 *
 * Channels are created at first start **and** after the permission grant (AC2): a channel created before the
 * user allows notifications still exists, but re-running is harmless because [NotificationManager.createNotificationChannel]
 * updates only the description of an existing channel and never overrides the user's importance choice.
 */
@Singleton
class NotificationChannels
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val explainer: Explainer,
    ) {
        fun ensureCreated() {
            val manager = NotificationManagerCompat.from(context)
            manager.createNotificationChannelGroup(
                NotificationChannelGroup(ChannelId.GROUP_ID, explainer.render(ChannelId.GROUP_NAME_KEY)),
            )
            // A channel id that the app no longer uses is deleted. Deleting and creating a channel with the
            // same id does not reset it (Android restores the old settings), and a silent channel can be the user's own
            // choice, so a sound change needs a new id in ChannelId; the old id then goes here.
            val known = ChannelId.entries.mapTo(HashSet()) { it.channelId }
            val platform = context.getSystemService(NotificationManager::class.java)
            platform?.notificationChannels?.forEach { old ->
                if (old.id !in known && old.group == ChannelId.GROUP_ID) platform.deleteNotificationChannel(old.id)
            }
            for (channel in ChannelId.entries) {
                val created =
                    NotificationChannel(
                        channel.channelId,
                        explainer.render(channel.titleKey),
                        importanceOf(channel.importance),
                    ).apply {
                        description = explainer.render(channel.descriptionKey)
                        group = ChannelId.GROUP_ID
                        setShowBadge(channel.importance != Importance.MIN)
                        enableVibration(channel.importance == Importance.HIGH)
                        // Must never imitate the system emergency alert; every other channel
                        // keeps the system default sound, so a suspicious message is audible.
                        if (channel == ChannelId.BROADCAST) setSound(null, null)
                    }
                manager.createNotificationChannel(created)
            }
        }

        /** True when the user (or the system) allows notifications for this app at all. */
        fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

        /** True when the user disabled one channel in system settings. */
        fun channelEnabled(channel: ChannelId): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return true
            val existing = manager.getNotificationChannel(channel.channelId) ?: return true
            return existing.importance != NotificationManager.IMPORTANCE_NONE
        }

        private fun importanceOf(importance: Importance): Int =
            when (importance) {
                Importance.HIGH -> NotificationManager.IMPORTANCE_HIGH
                Importance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
                Importance.LOW -> NotificationManager.IMPORTANCE_LOW
                Importance.MIN -> NotificationManager.IMPORTANCE_MIN
            }
    }
