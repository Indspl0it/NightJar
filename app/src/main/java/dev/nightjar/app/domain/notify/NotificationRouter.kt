package dev.nightjar.app.domain.notify

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.detect.DetectionDraft
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.settings.ClassifierSettings
import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict

/** Channel importance, mapped to `NotificationManager.IMPORTANCE_*` by the Android layer. */
enum class Importance { HIGH, DEFAULT, LOW, MIN }

/**
 * The six channels.
 *
 * Android keeps the settings of a channel id, also after the app deletes the channel. A changed channel (importance,
 * sound) therefore needs a new id, and `NotificationChannels` deletes the channels of the group whose id is not in
 * this list. The `_v2` ids replace the test-build channels that had no sound. Change an id only for such
 * a reason: the user loses the settings of that channel.
 */
enum class ChannelId(
    val channelId: String,
    val importance: Importance,
    val titleKey: TextKey,
    val descriptionKey: TextKey,
) {
    SUSPICIOUS("nc01_suspicious_v2", Importance.HIGH, TextKey.NOTIF_CHANNEL_SUSPICIOUS, TextKey.NOTIF_CHANNEL_SUSPICIOUS_DESC),
    HIDDEN("nc02_hidden_v2", Importance.DEFAULT, TextKey.NOTIF_CHANNEL_HIDDEN, TextKey.NOTIF_CHANNEL_HIDDEN_DESC),
    FIREWALL("nc03_firewall_v2", Importance.LOW, TextKey.NOTIF_CHANNEL_FIREWALL, TextKey.NOTIF_CHANNEL_FIREWALL_DESC),
    BROADCAST("nc04_broadcast_v2", Importance.LOW, TextKey.NOTIF_CHANNEL_BROADCAST, TextKey.NOTIF_CHANNEL_BROADCAST_DESC),
    STATUS("nc05_status_v2", Importance.DEFAULT, TextKey.NOTIF_CHANNEL_STATUS, TextKey.NOTIF_CHANNEL_STATUS_DESC),
    DIGEST("nc06_digest_v2", Importance.MIN, TextKey.NOTIF_CHANNEL_DIGEST, TextKey.NOTIF_CHANNEL_DIGEST_DESC),
    ;

    companion object {
        /** One channel group "NightJar" holds every channel. */
        const val GROUP_ID = "nightjar"

        val GROUP_NAME_KEY: TextKey = TextKey.NOTIF_CHANNEL_GROUP
    }
}

/**
 * Notification actions. The actions fit the state: Blocked -> Open. On hold -> Allow, Block.
 * Notify -> Open. `OPEN` opens the app; `ALLOW` (release) and `BLOCK` (keep it out) do not.
 */
enum class NotificationAction(
    val labelKey: TextKey,
    val opensUi: Boolean,
) {
    OPEN(TextKey.NOTIF_ACTION_OPEN, opensUi = true),
    ALLOW(TextKey.NOTIF_ACTION_ALLOW, opensUi = false),
    BLOCK(TextKey.NOTIF_ACTION_BLOCK, opensUi = false),
}

/**
 * What the notification is about; decides the id space and the renderer's style. NightJar posts no group summary:
 * Android makes its own summary for a large group and removes it with the last child.
 */
enum class AlertKind { MESSAGE, BURST, DIGEST, BROADCAST, PROTECTION_STATUS }

/**
 * A rendered notification, ready for `NotificationCompat`. Strings are already resolved, so the same plan can be
 * asserted in a JVM test without Android.
 *
 * @param publicTitle/publicText always set; before the first unlock they are also the visible content
 * @param alerting false means "post/update silently" (rate limit, burst continuation)
 * @param onlyAlertOnce true for burst updates
 * @param timeoutMs Android removes the notification after this time; null keeps it until the user removes it
 */
data class AlertPlan(
    val kind: AlertKind,
    val channel: ChannelId,
    val notificationId: Int,
    val title: String,
    val text: String,
    val bigText: String?,
    val publicTitle: String,
    val publicText: String,
    val groupKey: String?,
    val alerting: Boolean,
    val onlyAlertOnce: Boolean,
    val actions: List<NotificationAction>,
    val deepLink: String?,
    val inboxLines: List<String> = emptyList(),
    /** Small header text beside the app name: operator and SIM slot, when known. */
    val subText: String? = null,
    /** Row id of the message the alert is about, so a receiver action can look it up. Null for aggregate alerts. */
    val messageId: Long? = null,
    val timeoutMs: Long? = null,
)

/** Input to routing for one message. */
data class NotificationInput(
    val event: EventFacts,
    val messageId: Long,
    val severity: Severity,
    val detection: DetectionDraft? = null,
    val muted: Boolean = false,
    val beforeFirstUnlock: Boolean = false,
)

/** Formats the "time" fragment of a notification line. */
fun interface TimeFormatter {
    fun time(wallMs: Long): String
}

/** Deep-link scheme owned by the navigation graph. */
object DeepLinks {
    const val MESSAGE = "nightjar://message/"
    const val QUARANTINED = "nightjar://messages?filter=quarantined"
    const val ALL_MESSAGES = "nightjar://messages?filter=all"
    const val HEALTH = "nightjar://health"

    fun message(id: Long): String = MESSAGE + id
}

/** Stable notification ids: the same message or burst always updates the same notification. */
object NotificationIds {
    private const val STATUS_BASE = 0x0F00_0000
    private const val LEGACY_FIREWALL_SUMMARY = 0x0E00_0001
    private const val DIGEST = 0x0E00_0002

    fun message(messageId: Long): Int = mix(messageId)

    fun detection(
        detector: String,
        mergeKey: String,
    ): Int = mix((detector + ':' + mergeKey).hashCode().toLong())

    val digest: Int get() = DIGEST

    /** The id of the old per-message firewall summary (U7); the app removes a copy that an older version left. */
    val legacyFirewallSummary: Int get() = LEGACY_FIREWALL_SUMMARY

    fun status(kind: StatusKind): Int = STATUS_BASE + kind.ordinal

    /** States, each with its own notification so clearing one does not clear another. */
    enum class StatusKind { NOT_ACTIVE, SAFE_MODE, FIREWALL_DISABLED, VERSION_MISMATCH }

    /** 31-bit non-negative mix; a notification id of 0 is valid, so no offset is needed. */
    private fun mix(value: Long): Int {
        val x = value * -0x61c8864680b583ebL
        return ((x xor (x ushr 32)).toInt()) and 0x7FFF_FFFF
    }
}

/**
 * Decides what to post.
 *
 * Pure apart from the shared [RateLimiter], which keeps the rate-limit and burst state for the current process. That
 * is the lifetime the rate limits need: "one alert per burst" and "at most one alert per class per interval", not a
 * persisted policy. A router is built for each pass, so the state must not live in the router.
 *
 * The copy: the title is the type name and what NightJar did ("Settings
 * push blocked"), the text is one short sentence, and the actions fit the state ([AlertState]).
 */
class NotificationRouter(
    private val settings: ClassifierSettings,
    private val explainer: Explainer,
    private val rateLimiter: RateLimiter,
) {
    fun route(input: NotificationInput): AlertPlan? {
        val event = input.event
        // Self-test records and their canaries never notify.
        if (isTestTraffic(event) || input.muted) return null
        if (event.classId == ClassId.CB) return broadcastCopy(event, input.messageId)

        val channel = channelFor(input.severity, event.verdict) ?: return null
        val burst = input.detection?.takeIf { it.merges }
        val notificationId =
            if (burst != null) {
                NotificationIds.detection(burst.detectorId.wire, burst.mergeKey.orEmpty())
            } else {
                NotificationIds.message(input.messageId)
            }
        val burstKey = burst?.let { it.detectorId.wire + ':' + it.mergeKey }
        val firstBurstAlert = burstKey == null || rateLimiter.firstAlertOfBurst(burstKey)
        val alerting =
            firstBurstAlert &&
                rateLimiter.allowAlert(event.classId, event.receivedAtWallMs, settings.notifyRateLimitMin)

        val state = NotificationCopy.state(event)
        val title = burst?.let { explainer.detectionSummary(it, event) } ?: titleFor(event, state)
        val text = textFor(event, state)
        val bigText = bigTextFor(event, state, text)
        val subText = explainer.sim(event).ifEmpty { null }
        val publicTitle = publicTitle(input.severity)
        val visible =
            if (input.beforeFirstUnlock) {
                Triple(publicTitle, explainer.render(TextKey.NOTIF_BEFORE_UNLOCK), null)
            } else {
                Triple(title, text, bigText)
            }
        return AlertPlan(
            kind = if (burst != null) AlertKind.BURST else AlertKind.MESSAGE,
            channel = channel,
            notificationId = notificationId,
            title = visible.first,
            text = visible.second,
            bigText = visible.third,
            publicTitle = publicTitle,
            publicText = explainer.render(TextKey.NOTIF_PUBLIC_ACTIVITY),
            groupKey = groupKey(event),
            alerting = alerting,
            onlyAlertOnce = burst != null,
            actions = actionsFor(event, state),
            deepLink = DeepLinks.message(input.messageId),
            messageId = input.messageId,
            subText = subText,
        )
    }

    /**
     * U7 / the weekly digest of the last 7 days. One low-importance notification that never alerts and that
     * Android removes after [DigestSchedule.TIMEOUT_MS]. [DigestSchedule] decides when it may post; this only builds
     * it. Null when the week has nothing to report. The Digest setting (notify.digest) turns it off in [DigestSchedule].
     */
    fun weeklyDigest(counts: DigestCounts): AlertPlan? {
        if (counts.isEmpty) return null
        val publicText = explainer.render(TextKey.NOTIF_PUBLIC_ACTIVITY)
        return AlertPlan(
            kind = AlertKind.DIGEST,
            channel = ChannelId.DIGEST,
            notificationId = NotificationIds.digest,
            title = explainer.render(TextKey.NOTIF_DIGEST_TITLE),
            text =
                explainer.render(
                    TextKey.NOTIF_DIGEST_TEXT,
                    mapOf(
                        "hidden" to counts.hidden.toString(),
                        "suspicious" to counts.suspicious.toString(),
                        "held" to counts.held.toString(),
                        "blocked" to counts.blocked.toString(),
                    ),
                ),
            bigText = null,
            publicTitle = publicText,
            publicText = publicText,
            groupKey = null,
            alerting = false,
            onlyAlertOnce = true,
            actions = listOf(NotificationAction.OPEN),
            deepLink = DeepLinks.ALL_MESSAGES,
            timeoutMs = DigestSchedule.TIMEOUT_MS,
        )
    }

    /** Copies: low importance, never an emergency-style alert. Opens the broadcast row. */
    fun broadcastCopy(
        event: EventFacts,
        messageId: Long,
    ): AlertPlan? {
        if (!settings.notifyBroadcasts) return null
        if (isTestTraffic(event)) return null
        return AlertPlan(
            kind = AlertKind.BROADCAST,
            channel = ChannelId.BROADCAST,
            notificationId = NotificationIds.message(event.eventId.hashCode().toLong()),
            // The category title ("Emergency alert", "Area information") already says what happened.
            title = explainer.title(event),
            text = explainer.render(NotifTopic.BROADCAST.why),
            bigText = null,
            publicTitle = explainer.render(TextKey.NOTIF_PUBLIC_ACTIVITY),
            publicText = explainer.render(TextKey.NOTIF_PUBLIC_ACTIVITY),
            groupKey = groupKey(event),
            alerting = false,
            onlyAlertOnce = true,
            actions = listOf(NotificationAction.OPEN),
            deepLink = DeepLinks.message(messageId),
            messageId = messageId,
        )
    }

    /** A self-test record or a self-test canary (B3): stored, but never a notification. */
    private fun isTestTraffic(event: EventFacts): Boolean =
        event.flags.selfTest || event.flags.canaryClass != null || event.flags.runId != null

    private fun channelFor(
        severity: Severity,
        verdict: Verdict,
    ): ChannelId? =
        when {
            // The NOTIFY verdict always alerts, even for a class whose severity switch is off.
            // HIGH is above SUSPICIOUS and uses the same channel and switch.
            verdict == Verdict.NOTIFY -> if (severity >= Severity.SUSPICIOUS) ChannelId.SUSPICIOUS else ChannelId.HIDDEN

            severity >= Severity.SUSPICIOUS -> if (settings.notifySuspicious) ChannelId.SUSPICIOUS else null

            severity == Severity.NOTABLE -> if (settings.notifyNotable) ChannelId.HIDDEN else null

            else -> null
        }

    /** "Settings push blocked", "MMS notification on hold", "Silent SMS received", "Data message sent". */
    private fun titleFor(
        event: EventFacts,
        state: AlertState,
    ): String {
        val what = explainer.render(NotificationCopy.topic(event.classId).what)
        return explainer.render(NotificationCopy.titleKey(state, event.direction), mapOf("what" to what))
    }

    /**
     * A held message tells who sent it and what the user can do ("From +1•••••142. Allow it or block it.").
     * Every other state tells in one sentence what the message tried to do ("It tried to change your network
     * settings."). The title already says that NightJar blocked it.
     */
    private fun textFor(
        event: EventFacts,
        state: AlertState,
    ): String = if (state == AlertState.ON_HOLD) heldText(event) else whyText(event)

    private fun whyText(event: EventFacts): String {
        // Only the outgoing reasons name the app; the label is a package manager call, so it is read only for them.
        val args = if (event.direction == Direction.OUT) mapOf("app" to appName(event)) else emptyMap()
        return explainer.render(NotificationCopy.topic(event.classId).why, args)
    }

    private fun heldText(event: EventFacts): String {
        // "From" names a sender. An outgoing message has a recipient, so its text has no sender part.
        val who = whoShown(event)?.takeIf { event.direction != Direction.OUT }
        val key = NotificationCopy.heldKey(ClassRegistry.isReleasable(event.classId), withSender = who != null)
        return explainer.render(key, if (who != null) mapOf("who" to who) else emptyMap())
    }

    /**
     * Expanded text. A held message: what it tried to do, then the collapsed text (sender and choice). Every other
     * message: the collapsed text, then the sender or recipient. The SIM is in the header sub-text.
     */
    private fun bigTextFor(
        event: EventFacts,
        state: AlertState,
        text: String,
    ): String {
        if (state == AlertState.ON_HOLD) return whyText(event) + "\n" + text
        val who = whoShown(event) ?: return text
        val key = if (event.direction == Direction.OUT) TextKey.NOTIF_TO else TextKey.NOTIF_FROM
        return text + "\n" + explainer.render(key, mapOf("who" to who))
    }

    /** The name of the app that sent an outgoing message, or "An app" when the payload did not name one. */
    private fun appName(event: EventFacts): String =
        event.callingPackage
            ?.let { explainer.whoOrNull(event.copy(destination = null)) }
            ?: explainer.render(TextKey.NOTIF_SOME_APP)

    /** The sender or recipient line, or null when `privacy.hideInNotifications` keeps numbers out (DOMAIN-X07). */
    private fun whoShown(event: EventFacts): String? = if (settings.hideNumbersInNotifications) null else explainer.whoOrNull(event)

    private fun publicTitle(severity: Severity): String =
        explainer.render(
            if (severity >= Severity.SUSPICIOUS) TextKey.NOTIF_PUBLIC_SUSPICIOUS else TextKey.NOTIF_PUBLIC_ACTIVITY,
        )

    private fun groupKey(event: EventFacts): String = GROUP_KEY + ':' + ClassRegistry.familyOf(event.classId).wire

    /**
     * U8: Blocked -> Open. On hold -> Allow (only for a class that can be released) and Block. Every other
     * state -> Open. A blocked message never offers "Block similar". A tap on the notification always opens the message.
     */
    private fun actionsFor(
        event: EventFacts,
        state: AlertState,
    ): List<NotificationAction> =
        when (state) {
            AlertState.ON_HOLD -> {
                if (ClassRegistry.isReleasable(event.classId)) {
                    listOf(NotificationAction.ALLOW, NotificationAction.BLOCK)
                } else {
                    listOf(NotificationAction.OPEN, NotificationAction.BLOCK)
                }
            }

            AlertState.BLOCKED, AlertState.SEEN -> {
                listOf(NotificationAction.OPEN)
            }
        }

    companion object {
        const val GROUP_KEY = "nightjar.group"
    }
}
