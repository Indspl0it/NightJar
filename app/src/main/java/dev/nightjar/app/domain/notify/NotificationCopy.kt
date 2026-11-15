package dev.nightjar.app.domain.notify

import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate

/**
 * What NightJar did with the message, as the notification tells it. It decides the
 * end of the title, the text and the actions. Only the real fate counts: a BLOCK verdict that the payload could not
 * enforce is not "blocked".
 */
enum class AlertState { BLOCKED, ON_HOLD, SEEN }

/**
 * The subject of a notification (U8): [what] is the type name at the start of the title ("Settings push
 * blocked"), [why] is the one sentence that says what the message tried to do ("It tried to change your network
 * settings."). Several classes share a topic: the user needs to know what the message tried to do, not the protocol.
 */
enum class NotifTopic(
    val what: TextKey,
    val why: TextKey,
) {
    NORMAL(TextKey.NOTIF_WHAT_NORMAL, TextKey.NOTIF_WHY_NORMAL),
    SILENT(TextKey.NOTIF_WHAT_SILENT, TextKey.NOTIF_WHY_SILENT),
    FLASH(TextKey.NOTIF_WHAT_FLASH, TextKey.NOTIF_WHY_FLASH),
    REPLACE(TextKey.NOTIF_WHAT_REPLACE, TextKey.NOTIF_WHY_REPLACE),
    SETTINGS(TextKey.NOTIF_WHAT_SETTINGS, TextKey.NOTIF_WHY_SETTINGS),
    LOCATION(TextKey.NOTIF_WHAT_LOCATION, TextKey.NOTIF_WHY_LOCATION),
    REMOTE(TextKey.NOTIF_WHAT_REMOTE, TextKey.NOTIF_WHY_REMOTE),
    WEB_LINK(TextKey.NOTIF_WHAT_WEB_LINK, TextKey.NOTIF_WHY_WEB_LINK),
    MMS(TextKey.NOTIF_WHAT_MMS, TextKey.NOTIF_WHY_MMS),
    PUSH(TextKey.NOTIF_WHAT_PUSH, TextKey.NOTIF_WHY_PUSH),
    SIM(TextKey.NOTIF_WHAT_SIM, TextKey.NOTIF_WHY_SIM),
    PHONE(TextKey.NOTIF_WHAT_PHONE, TextKey.NOTIF_WHY_PHONE),
    APP_DATA(TextKey.NOTIF_WHAT_APP_DATA, TextKey.NOTIF_WHY_APP_DATA),
    INJECTED(TextKey.NOTIF_WHAT_INJECTED, TextKey.NOTIF_WHY_INJECTED),
    DAMAGED(TextKey.NOTIF_WHAT_DAMAGED, TextKey.NOTIF_WHY_DAMAGED),
    UNREADABLE(TextKey.NOTIF_WHAT_UNREADABLE, TextKey.NOTIF_WHY_UNREADABLE),
    INCOMPLETE(TextKey.NOTIF_WHAT_INCOMPLETE, TextKey.NOTIF_WHY_INCOMPLETE),
    VOICEMAIL(TextKey.NOTIF_WHAT_VOICEMAIL, TextKey.NOTIF_WHY_VOICEMAIL),
    SERVICE(TextKey.NOTIF_WHAT_SERVICE, TextKey.NOTIF_WHY_SERVICE),
    RARE(TextKey.NOTIF_WHAT_RARE, TextKey.NOTIF_WHY_RARE),
    BROADCAST(TextKey.NOTIF_WHAT_BROADCAST, TextKey.NOTIF_WHY_BROADCAST),
    OUT_HIDDEN(TextKey.NOTIF_WHAT_OUT_HIDDEN, TextKey.NOTIF_WHY_OUT_HIDDEN),
    OUT_DATA(TextKey.NOTIF_WHAT_OUT_DATA, TextKey.NOTIF_WHY_OUT_DATA),
    OUT_APP(TextKey.NOTIF_WHAT_OUT_APP, TextKey.NOTIF_WHY_OUT_APP),
    OUT_SIM(TextKey.NOTIF_WHAT_OUT_SIM, TextKey.NOTIF_WHY_OUT_SIM),
    OUT_VOICEMAIL(TextKey.NOTIF_WHAT_OUT_VOICEMAIL, TextKey.NOTIF_WHY_OUT_VOICEMAIL),
}

/** Maps a message to its [AlertState] and [NotifTopic]. Pure, so the copy of every class is testable on the JVM. */
object NotificationCopy {
    fun state(event: EventFacts): AlertState =
        when {
            event.fate == Fate.QUARANTINED -> AlertState.ON_HOLD
            event.fate == Fate.BLOCKED || event.blockedByOutgoingGate -> AlertState.BLOCKED
            else -> AlertState.SEEN
        }

    /** The title format of [state]: "… blocked", "… on hold", or "… received" / "… sent". */
    fun titleKey(
        state: AlertState,
        direction: Direction,
    ): TextKey =
        when (state) {
            AlertState.BLOCKED -> TextKey.NOTIF_TITLE_BLOCKED
            AlertState.ON_HOLD -> TextKey.NOTIF_TITLE_HELD
            AlertState.SEEN -> if (direction == Direction.OUT) TextKey.NOTIF_TITLE_SENT else TextKey.NOTIF_TITLE_RECEIVED
        }

    /**
     * The text of a held message: the sender, then what the user can do. The words match the actions:
     * "Allow it or block it." when the type can be released, else "Open it or block it." [withSender] is false when
     * the notification must not show the sender.
     */
    fun heldKey(
        releasable: Boolean,
        withSender: Boolean,
    ): TextKey =
        when {
            releasable && withSender -> TextKey.NOTIF_HELD_ALLOW_FROM
            releasable -> TextKey.NOTIF_HELD_ALLOW
            withSender -> TextKey.NOTIF_HELD_OPEN_FROM
            else -> TextKey.NOTIF_HELD_OPEN
        }

    /** Exhaustive, so a new class cannot ship without notification copy. */
    @Suppress("CyclomaticComplexMethod")
    fun topic(classId: ClassId): NotifTopic =
        when (classId) {
            ClassId.NORMAL -> NotifTopic.NORMAL

            ClassId.SILENT -> NotifTopic.SILENT

            ClassId.FLASH -> NotifTopic.FLASH

            ClassId.REPLACE -> NotifTopic.REPLACE

            ClassId.WAP_CP -> NotifTopic.SETTINGS

            ClassId.WAP_SUPL, ClassId.LOC_SUPL -> NotifTopic.LOCATION

            ClassId.WAP_DM -> NotifTopic.REMOTE

            ClassId.WAP_SISL -> NotifTopic.WEB_LINK

            ClassId.WAP_MMS -> NotifTopic.MMS

            ClassId.WAP_OTHER, ClassId.WAP_CO, ClassId.WAP_DS, ClassId.WAP_EMN, ClassId.WAP_DRM, ClassId.WAP_VCARD,
            ClassId.WAP_IMPS,
            -> NotifTopic.PUSH

            ClassId.SIM_DD, ClassId.SIM_STORED, ClassId.CB_SIM -> NotifTopic.SIM

            ClassId.ME_DOWNLOAD, ClassId.ME_DEPERSO -> NotifTopic.PHONE

            ClassId.DATA_PORT, ClassId.APP_TOKEN, ClassId.BINARY -> NotifTopic.APP_DATA

            ClassId.INJECTED -> NotifTopic.INJECTED

            ClassId.MALFORMED_SMS, ClassId.WAP_UNREADABLE -> NotifTopic.DAMAGED

            ClassId.UNREADABLE_SMS -> NotifTopic.UNREADABLE

            ClassId.MULTIPART_INCOMPLETE -> NotifTopic.INCOMPLETE

            ClassId.MWI, ClassId.VVM -> NotifTopic.VOICEMAIL

            ClassId.MISSED_CALL, ClassId.CARRIER, ClassId.RCS_PROV, ClassId.OEM, ClassId.STATUS, ClassId.RETURN_CALL,
            -> NotifTopic.SERVICE

            ClassId.CLASS3, ClassId.TELEMATIC, ClassId.EMPTY, ClassId.COMPRESSED, ClassId.SMART -> NotifTopic.RARE

            ClassId.CB -> NotifTopic.BROADCAST

            ClassId.MO_UNPERSISTED, ClassId.MO_RAW -> NotifTopic.OUT_HIDDEN

            ClassId.MO_DATA -> NotifTopic.OUT_DATA

            ClassId.MO_VISIBLE -> NotifTopic.OUT_APP

            ClassId.MO_STK -> NotifTopic.OUT_SIM

            ClassId.MO_VVM -> NotifTopic.OUT_VOICEMAIL
        }
}
