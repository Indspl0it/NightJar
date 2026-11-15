package dev.nightjar.app.domain.notify

import dev.nightjar.app.domain.TestEvents
import dev.nightjar.app.domain.detect.DetectionDraft
import dev.nightjar.app.domain.detect.DetectorId
import dev.nightjar.app.domain.model.CbInfo
import dev.nightjar.app.domain.model.EventFlags
import dev.nightjar.app.domain.settings.ClassifierSettings
import dev.nightjar.app.domain.text.DateFormatter
import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.app.domain.text.MapStrings
import dev.nightjar.app.domain.text.PackageLabels
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Routing, grouping, bursts and the rate limit. */
class NotificationRouterTest {
    private val explainer = Explainer(MapStrings(), PackageLabels { "Example" }, DateFormatter { "5 Nov 2026" })

    private fun router(settings: ClassifierSettings = ClassifierSettings()): NotificationRouter =
        NotificationRouter(settings, explainer, RateLimiter())

    private fun input(
        classId: ClassId = ClassId.WAP_CP,
        severity: Severity = Severity.SUSPICIOUS,
        verdict: Verdict = Verdict.ALLOW,
        fate: Fate = Fate.SYSTEM,
        muted: Boolean = false,
        beforeFirstUnlock: Boolean = false,
        detection: DetectionDraft? = null,
        at: Long = 1_000_000L,
        messageId: Long = 1L,
    ): NotificationInput {
        val event =
            TestEvents.inbound(classId, at = at, eventId = "e-$messageId").copy(
                verdict = verdict,
                fate = fate,
                subId = 1,
                slot = 0,
            )
        return NotificationInput(event, messageId, severity, detection, muted, beforeFirstUnlock)
    }

    @Test
    fun `suspicious messages go to the alert channel and alert`() {
        val plan = router().route(input())!!
        assertEquals(ChannelId.SUSPICIOUS, plan.channel)
        assertTrue(plan.alerting)
        assertTrue(plan.title.isNotBlank())
        assertTrue(plan.text.isNotBlank())
    }

    @Test
    fun `high messages go to the alert channel like suspicious ones`() {
        val plan = router().route(input(severity = Severity.HIGH))!!
        assertEquals(ChannelId.SUSPICIOUS, plan.channel)
        assertTrue(plan.alerting)
        assertEquals(explainer.render(TextKey.NOTIF_PUBLIC_SUSPICIOUS), plan.publicTitle)
        assertEquals(ChannelId.SUSPICIOUS, router().route(input(severity = Severity.HIGH, verdict = Verdict.NOTIFY))!!.channel)
        assertNull(router(ClassifierSettings(notifySuspicious = false)).route(input(severity = Severity.HIGH)))
    }

    @Test
    fun `a burst that alerted in an earlier pass does not alert again`() {
        val limiter = RateLimiter()
        val burst =
            DetectionDraft(
                DetectorId.SILENT_PING,
                Severity.SUSPICIOUS,
                TextKey.DET_SILENT_BURST,
                emptyMap(),
                listOf("a"),
                1L,
                1L,
                3,
                mergeKey = "burst-a",
            )
        val first = NotificationRouter(ClassifierSettings(), explainer, limiter).route(input(detection = burst, messageId = 1))!!
        val next = NotificationRouter(ClassifierSettings(), explainer, limiter)
        val second = next.route(input(detection = burst, at = 1_000_000L + 3_600_000L, messageId = 2))!!
        assertTrue(first.alerting)
        assertFalse(second.alerting)
    }

    @Test
    fun `notable messages need the notable switch`() {
        assertNull(router().route(input(severity = Severity.NOTABLE)))
        val plan = router(ClassifierSettings(notifyNotable = true)).route(input(severity = Severity.NOTABLE))!!
        assertEquals(ChannelId.HIDDEN, plan.channel)
    }

    @Test
    fun `the NOTIFY verdict alerts even with the severity switch off`() {
        val plan = router().route(input(severity = Severity.NOTABLE, verdict = Verdict.NOTIFY))!!
        assertEquals(ChannelId.HIDDEN, plan.channel)
        assertTrue(plan.alerting)
        val suspicious = router().route(input(severity = Severity.SUSPICIOUS, verdict = Verdict.NOTIFY))!!
        assertEquals(ChannelId.SUSPICIOUS, suspicious.channel)
    }

    @Test
    fun `info messages do not notify`() {
        assertNull(router(ClassifierSettings(notifyNotable = true)).route(input(severity = Severity.INFO)))
    }

    @Test
    fun `a muted sender or class is not notified`() {
        assertNull(router().route(input(muted = true)))
    }

    @Test
    fun `self test records never notify`() {
        val event =
            TestEvents.inbound(ClassId.WAP_CP).copy(
                flags =
                    dev.nightjar.app.domain.model
                        .EventFlags(selfTest = true),
            )
        val plan = router().route(NotificationInput(event, 1, Severity.SUSPICIOUS))
        assertNull(plan)
    }

    @Test
    fun `debug injections notify like real traffic`() {
        // Debug injections are used to test notifications, so the router does not filter them.
        val event = TestEvents.inbound(ClassId.WAP_CP).copy(flags = EventFlags(debug = true))
        assertNotNull(router().route(NotificationInput(event, 1, Severity.HIGH)))
    }

    @Test
    fun `broadcast copies need the switch and never alert loudly`() {
        val event =
            TestEvents.inbound(ClassId.CB).copy(
                cb =
                    dev.nightjar.app.domain.model
                        .CbInfo(1, 4370, 1, 1, true),
            )
        assertNull(router().route(NotificationInput(event, 2, Severity.INFO)))
        val plan = router(ClassifierSettings(notifyBroadcasts = true)).route(NotificationInput(event, 2, Severity.INFO))!!
        assertEquals(ChannelId.BROADCAST, plan.channel)
        assertFalse(plan.alerting)
        assertTrue(plan.onlyAlertOnce)
        // A broadcast copy is not held, so it opens the broadcast row, not the quarantine list.
        assertEquals(DeepLinks.message(2), plan.deepLink)
    }

    @Test
    fun `a growing burst keeps one notification id and alerts once`() {
        val router = router()
        val first =
            DetectionDraft(
                detectorId = DetectorId.SILENT_PING,
                severity = Severity.SUSPICIOUS,
                summaryKey = TextKey.DET_SILENT_BURST,
                summaryArgs = mapOf("count" to "3", "minutes" to "2", "senders" to "1"),
                eventIds = listOf("e-1"),
                firstAtWallMs = 1_000_000L,
                lastAtWallMs = 1_000_000L,
                count = 3,
                mergeKey = "silent-burst:1000000",
            )
        val second = first.copy(summaryArgs = mapOf("count" to "6", "minutes" to "10", "senders" to "2"), count = 6)

        val planA = router.route(input(detection = first, at = 1_000_000L, messageId = 1))!!
        val planB = router.route(input(detection = second, at = 1_060_000L, messageId = 2))!!
        assertEquals(planA.notificationId, planB.notificationId)
        assertTrue(planA.alerting)
        assertTrue(planB.onlyAlertOnce)
        assertFalse(planB.alerting, "a burst update must not ring twice")
        assertTrue(planB.title.contains("6"))
    }

    @Test
    fun `two different bursts get different notification ids`() {
        val one =
            DetectionDraft(
                DetectorId.SILENT_PING,
                Severity.SUSPICIOUS,
                TextKey.DET_SILENT_BURST,
                emptyMap(),
                listOf("a"),
                1L,
                1L,
                3,
                mergeKey = "burst-a",
            )
        val two = one.copy(mergeKey = "burst-b")
        val planA = router().route(input(detection = one, messageId = 1))!!
        val planB = router().route(input(detection = two, messageId = 2))!!
        assertNotEquals(planA.notificationId, planB.notificationId)
    }

    @Test
    fun `at most one alert per class per rate limit window`() {
        val router = router(ClassifierSettings(notifyRateLimitMin = 5))
        val first = router.route(input(at = 1_000_000L, messageId = 1))!!
        val second = router.route(input(at = 1_000_000L + 60_000L, messageId = 2))!!
        assertTrue(first.alerting)
        assertFalse(second.alerting)
        assertNotEquals(first.notificationId, second.notificationId)

        val later = router.route(input(at = 1_000_000L + 6 * 60_000L, messageId = 3))!!
        assertTrue(later.alerting)
    }

    @Test
    fun `a 15 minute limit keeps later alerts of a class silent`() {
        val router = router(ClassifierSettings(notifyRateLimitMin = 15))
        assertTrue(router.route(input(at = 1_000_000L, messageId = 1))!!.alerting)
        assertFalse(router.route(input(at = 1_000_000L + 14 * 60_000L, messageId = 2))!!.alerting)
        // Another class has its own window.
        assertTrue(router.route(input(classId = ClassId.WAP_SUPL, at = 1_000_000L + 60_000L, messageId = 3))!!.alerting)
        assertTrue(router.route(input(at = 1_000_000L + 15 * 60_000L, messageId = 4))!!.alerting)
    }

    @Test
    fun `the public version carries no sender or content`() {
        val plan = router().route(input())!!
        assertEquals(explainer.render(TextKey.NOTIF_PUBLIC_SUSPICIOUS), plan.publicTitle)
        assertFalse(plan.publicText.contains("9876"))
        assertFalse(plan.publicTitle.contains("Settings push"))
    }

    @Test
    fun `hide in notifications keeps the sender out of the text`() {
        val plan = router(ClassifierSettings(hideNumbersInNotifications = true)).route(input())!!
        assertFalse(plan.text.contains("9876"), plan.text)
        assertFalse(plan.bigText.orEmpty().contains("9876"), plan.bigText)
    }

    @Test
    fun `before the first unlock only the public text is visible`() {
        val plan = router().route(input(beforeFirstUnlock = true))!!
        assertEquals(plan.publicTitle, plan.title)
        assertEquals(explainer.render(TextKey.NOTIF_BEFORE_UNLOCK), plan.text)
        assertNull(plan.bigText)
    }

    @Test
    fun `actions fit the state of the message`() {
        // U8: Notify -> Open.
        val seen = router().route(input(classId = ClassId.WAP_CP))!!
        assertEquals(listOf(NotificationAction.OPEN), seen.actions)

        // U8: Blocked -> Open. A blocked message never offers "Block similar" or Block.
        val blocked = router().route(input(classId = ClassId.WAP_CP, verdict = Verdict.BLOCK, fate = Fate.BLOCKED))!!
        assertEquals(listOf(NotificationAction.OPEN), blocked.actions)

        // U8: On hold -> Allow, Block.
        val held = router().route(input(classId = ClassId.WAP_CP, verdict = Verdict.QUARANTINE, fate = Fate.QUARANTINED))!!
        assertEquals(listOf(NotificationAction.ALLOW, NotificationAction.BLOCK), held.actions)

        // A malformed SMS is never released, so a held one offers Open and Block, not Allow.
        val malformed = router().route(input(classId = ClassId.MALFORMED_SMS, fate = Fate.QUARANTINED))!!
        assertEquals(listOf(NotificationAction.OPEN, NotificationAction.BLOCK), malformed.actions)

        val outbound =
            router().route(
                NotificationInput(TestEvents.outbound(ClassId.MO_DATA, callingPackage = "com.example.app"), 5, Severity.SUSPICIOUS),
            )!!
        assertEquals(listOf(NotificationAction.OPEN), outbound.actions)
    }

    @Test
    fun `a blocked settings push says what happened in plain words`() {
        // Expected: "Settings push blocked" / "It tried to change your network settings." / Open.
        val plan = router().route(input(classId = ClassId.WAP_CP, verdict = Verdict.BLOCK, fate = Fate.BLOCKED))!!
        assertEquals("Settings push blocked", plan.title)
        assertEquals("It tried to change your network settings.", plan.text)
        assertTrue(plan.bigText!!.startsWith(plan.text), plan.bigText)
        assertFalse(plan.title.contains("APN"), plan.title)
    }

    @Test
    fun `a held message names the masked sender and the choice`() {
        // Expected: "Flash SMS on hold" / "From +1•••••142. Allow it or block it." / Allow, Block.
        val masked = Explainer(MapStrings(), PackageLabels { "Example" }, DateFormatter { "5 Nov 2026" }, maskNumbers = { true })
        val router = NotificationRouter(ClassifierSettings(), masked, RateLimiter())
        val held = router.route(input(classId = ClassId.FLASH, verdict = Verdict.QUARANTINE, fate = Fate.QUARANTINED))!!
        assertEquals("Flash SMS on hold", held.title)
        assertTrue(held.text.startsWith("From "), held.text)
        assertTrue(held.text.endsWith(". Allow it or block it."), held.text)
        assertFalse(held.text.contains("9876"), "a masked sender only: ${held.text}")
        // The expanded text keeps what the message did, then the sender and the choice.
        assertEquals("Android showed it on your screen but did not save it.\n" + held.text, held.bigText)
    }

    @Test
    fun `a held message without a shown sender has the choice only`() {
        val hidden = router(ClassifierSettings(hideNumbersInNotifications = true))
        val held = hidden.route(input(classId = ClassId.WAP_MMS, verdict = Verdict.QUARANTINE, fate = Fate.QUARANTINED))!!
        assertEquals("MMS notification on hold", held.title)
        assertEquals("Allow it or block it.", held.text)

        // A malformed SMS is never released. Its actions are Open and Block, and the text says so.
        val malformed = hidden.route(input(classId = ClassId.MALFORMED_SMS, fate = Fate.QUARANTINED))!!
        assertEquals("Open it or block it.", malformed.text)
        assertEquals(listOf(NotificationAction.OPEN, NotificationAction.BLOCK), malformed.actions)
    }

    @Test
    fun `a seen message has its own title and text`() {
        val seen = router().route(input(classId = ClassId.SILENT))!!
        assertEquals("Silent SMS received", seen.title)
        assertEquals("It reached your phone and showed you nothing.", seen.text)
    }

    @Test
    fun `an outgoing message names the app and the recipient`() {
        val plan =
            router().route(
                NotificationInput(TestEvents.outbound(ClassId.MO_DATA, callingPackage = "com.example.app"), 5, Severity.SUSPICIOUS),
            )!!
        assertEquals("Data message sent", plan.title)
        assertEquals("Example sends this data message in the background.", plan.text)
        assertTrue(plan.bigText!!.contains("To "), plan.bigText)

        val noApp =
            router().route(NotificationInput(TestEvents.outbound(ClassId.MO_UNPERSISTED, callingPackage = null), 6, Severity.SUSPICIOUS))!!
        assertEquals("An app sends this message in the background.", noApp.text)
    }

    @Test
    fun `self test canaries never notify`() {
        // B3: a canary that reaches the app is never a real alert, whichever self-test field the payload set.
        val canary =
            TestEvents.inbound(ClassId.WAP_CP).copy(
                verdict = Verdict.BLOCK,
                fate = Fate.BLOCKED,
                flags = EventFlags(canaryClass = ClassId.WAP_CP),
            )
        assertNull(router().route(NotificationInput(canary, 1, Severity.HIGH)))
        val withRun = canary.copy(flags = EventFlags(runId = "run-1"))
        assertNull(router().route(NotificationInput(withRun, 2, Severity.HIGH)))
        val cbCanary =
            TestEvents.inbound(ClassId.CB).copy(
                cb = CbInfo(1, 4370, 1, 1, true),
                flags = EventFlags(selfTest = true, canaryClass = ClassId.CB),
            )
        assertNull(router(ClassifierSettings(notifyBroadcasts = true)).route(NotificationInput(cbCanary, 3, Severity.INFO)))
    }

    @Test
    fun `the notification deep links to the message`() {
        val plan = router().route(input(messageId = 42))!!
        assertEquals(DeepLinks.message(42), plan.deepLink)
    }

    @Test
    fun `the weekly digest counts the week and never alerts`() {
        val plan = router().weeklyDigest(DigestCounts(hidden = 9, suspicious = 2, held = 3, blocked = 1))!!
        assertEquals(ChannelId.DIGEST, plan.channel)
        assertEquals(AlertKind.DIGEST, plan.kind)
        assertFalse(plan.alerting)
        assertEquals("Hidden messages: 9. Suspicious: 2. On hold: 3. Blocked: 1.", plan.text)
        assertEquals(DigestSchedule.TIMEOUT_MS, plan.timeoutMs, "the digest must not stay in the shade")
        assertNull(plan.groupKey, "no group, so no group summary can stay behind")
        assertEquals(listOf(NotificationAction.OPEN), plan.actions)
        assertNull(router().weeklyDigest(DigestCounts(0, 0, 0, 0)))
    }

    @Test
    fun `message notifications have no timeout and no summary kind`() {
        val plan = router().route(input())!!
        assertNull(plan.timeoutMs)
        assertEquals(AlertKind.MESSAGE, plan.kind)
    }

    @Test
    fun `group keys separate class families`() {
        val wap = router().route(input(classId = ClassId.WAP_CP))!!
        val sim = router().route(input(classId = ClassId.SIM_DD, severity = Severity.SUSPICIOUS))!!
        assertNotEquals(wap.groupKey, sim.groupKey)
    }

    @Test
    fun `notification ids are stable and non negative`() {
        assertEquals(NotificationIds.message(7), NotificationIds.message(7))
        assertTrue(NotificationIds.message(Long.MAX_VALUE) >= 0)
        assertNotEquals(NotificationIds.message(7), NotificationIds.message(8))
        assertNotEquals(
            NotificationIds.status(NotificationIds.StatusKind.SAFE_MODE),
            NotificationIds.status(NotificationIds.StatusKind.NOT_ACTIVE),
        )
    }
}
