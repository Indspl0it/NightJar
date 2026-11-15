package dev.nightjar.app.notifications

import dev.nightjar.app.domain.notify.AlertPlan
import dev.nightjar.app.domain.notify.ChannelId
import dev.nightjar.app.domain.notify.NotificationIds
import dev.nightjar.app.domain.notify.StatusPlanner
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ProtectionState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Protection-status notifications.
 *
 * One notification per condition, each removed as soon as its condition clears: a stuck "not active" alert after the
 * module came back would be worse than no alert at all. A condition posts once when it starts ([StatusPlanner]), so a
 * notification that the user removed does not come back on the next refresh.
 */
@Singleton
class StatusNotifier
    @Inject
    constructor(
        private val notifier: Notifier,
        private val explainer: dev.nightjar.app.domain.text.Explainer,
    ) {
        private val planner = StatusPlanner()

        /** Posts what applies and clears what no longer does. */
        fun sync(
            report: ProtectionReport,
            moduleNewerThanApp: Boolean,
            oncePerBootAlreadyPosted: Set<NotificationIds.StatusKind> = emptySet(),
        ) {
            val wanted = HashSet<NotificationIds.StatusKind>(4)
            if (moduleNewerThanApp) wanted += NotificationIds.StatusKind.VERSION_MISMATCH
            when {
                report.state == ProtectionState.NOT_ACTIVE -> {
                    wanted += NotificationIds.StatusKind.NOT_ACTIVE
                }

                report.state == ProtectionState.SAFE_MODE -> {
                    wanted += NotificationIds.StatusKind.SAFE_MODE
                }

                report.state == ProtectionState.MONITOR_ONLY && !report.firewallAvailable -> {
                    wanted += NotificationIds.StatusKind.FIREWALL_DISABLED
                }
            }
            val changes = planner.plan(wanted, oncePerBootAlreadyPosted)
            for (kind in changes.post) notifier.post(plan(kind))
            for (kind in changes.cancel) notifier.cancelStatus(kind)
        }

        private fun plan(kind: NotificationIds.StatusKind): AlertPlan {
            val title =
                when (kind) {
                    NotificationIds.StatusKind.NOT_ACTIVE -> TextKey.NOTIF_STATUS_NOT_ACTIVE
                    NotificationIds.StatusKind.SAFE_MODE -> TextKey.NOTIF_STATUS_SAFE_MODE
                    NotificationIds.StatusKind.FIREWALL_DISABLED -> TextKey.NOTIF_STATUS_FIREWALL_OFF
                    NotificationIds.StatusKind.VERSION_MISMATCH -> TextKey.NOTIF_STATUS_VERSION
                }
            val body =
                when (kind) {
                    NotificationIds.StatusKind.NOT_ACTIVE -> TextKey.NOTIF_STATUS_NOT_ACTIVE_TEXT
                    NotificationIds.StatusKind.SAFE_MODE -> TextKey.NOTIF_STATUS_SAFE_MODE_TEXT
                    NotificationIds.StatusKind.FIREWALL_DISABLED -> TextKey.NOTIF_STATUS_FIREWALL_OFF_TEXT
                    NotificationIds.StatusKind.VERSION_MISMATCH -> TextKey.NOTIF_STATUS_VERSION_TEXT
                }
            return AlertPlan(
                kind = dev.nightjar.app.domain.notify.AlertKind.PROTECTION_STATUS,
                channel = ChannelId.STATUS,
                notificationId = NotificationIds.status(kind),
                title = explainer.render(title),
                text = explainer.render(body),
                bigText = null,
                publicTitle = explainer.render(TextKey.NOTIF_PUBLIC_ACTIVITY),
                publicText = explainer.render(TextKey.NOTIF_PUBLIC_ACTIVITY),
                groupKey = null,
                alerting = true,
                onlyAlertOnce = true,
                actions = listOf(dev.nightjar.app.domain.notify.NotificationAction.OPEN),
                deepLink = dev.nightjar.app.domain.notify.DeepLinks.HEALTH,
            )
        }

        /** Title for a status notification, exposed for tests of the state → copy mapping. */
        fun titleKeyFor(report: ProtectionReport): TextKey? =
            when (report.state) {
                ProtectionState.NOT_ACTIVE -> {
                    TextKey.NOTIF_STATUS_NOT_ACTIVE
                }

                ProtectionState.SAFE_MODE -> {
                    TextKey.NOTIF_STATUS_SAFE_MODE
                }

                ProtectionState.MONITOR_ONLY -> {
                    if (!report.firewallAvailable) TextKey.NOTIF_STATUS_FIREWALL_OFF else null
                }

                else -> {
                    null
                }
            }
    }
