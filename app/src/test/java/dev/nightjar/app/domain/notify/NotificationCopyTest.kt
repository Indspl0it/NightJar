package dev.nightjar.app.domain.notify

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.TestEvents
import dev.nightjar.app.domain.text.MapStrings
import dev.nightjar.app.domain.text.Templates
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every class that can notify has plain-words copy, and the state decides the title and the actions. */
class NotificationCopyTest {
    private val strings = MapStrings()

    @Test
    fun `every class has a plain words subject and one reason sentence`() {
        for (classId in ClassId.entries) {
            val topic = NotificationCopy.topic(classId)
            val what = strings.get(topic.what)
            val why = strings.get(topic.why)
            assertTrue(what.isNotBlank(), "$classId what")
            assertTrue(why.endsWith("."), "$classId: the reason is one full sentence: $why")
            assertEquals(1, why.count { it == '.' }, "$classId: one sentence only: $why")
            // The type name starts the title ("Settings push blocked"), so it starts with a capital letter.
            assertTrue(what.first().isUpperCase(), "$classId: $what")
            assertFalse(what.contains("APN") || what.contains("WAP") || what.contains("PDU"), "$classId: no protocol names: $what")
            assertFalse(what.contains("("), "$classId: no brackets in a notification title: $what")
        }
    }

    @Test
    fun `every title has 5 words or fewer`() {
        // Copy standard section 3: a notification title has 5 words or fewer.
        for (topic in NotifTopic.entries) {
            val what = strings.get(topic.what)
            for (state in AlertState.entries) {
                for (direction in Direction.entries) {
                    val title = Templates.format(strings.get(NotificationCopy.titleKey(state, direction)), mapOf("what" to what))
                    assertTrue(title.split(' ').size <= 5, "$topic $state $direction: $title")
                }
            }
        }
    }

    @Test
    fun `outgoing classes have outgoing copy`() {
        for (profile in ClassRegistry.all().filter { it.direction == Direction.OUT }) {
            val topic = NotificationCopy.topic(profile.classId)
            assertTrue(topic.name.startsWith("OUT_"), "${profile.classId} -> $topic")
        }
    }

    @Test
    fun `the state comes from the real fate`() {
        val event = TestEvents.inbound(ClassId.WAP_CP)
        assertEquals(AlertState.BLOCKED, NotificationCopy.state(event.copy(fate = Fate.BLOCKED)))
        assertEquals(AlertState.ON_HOLD, NotificationCopy.state(event.copy(fate = Fate.QUARANTINED)))
        assertEquals(AlertState.SEEN, NotificationCopy.state(event.copy(fate = Fate.SYSTEM)))
        // A BLOCK verdict that the payload could not enforce is not "Blocked".
        assertEquals(AlertState.SEEN, NotificationCopy.state(event.copy(verdict = dev.nightjar.contract.Verdict.BLOCK)))
        // The system blocked-numbers list is not NightJar.
        assertEquals(AlertState.SEEN, NotificationCopy.state(event.copy(fate = Fate.BLOCKLIST)))
    }

    @Test
    fun `titles per state and the text of a held message`() {
        assertEquals(TextKey.NOTIF_TITLE_BLOCKED, NotificationCopy.titleKey(AlertState.BLOCKED, Direction.IN))
        assertEquals(TextKey.NOTIF_TITLE_HELD, NotificationCopy.titleKey(AlertState.ON_HOLD, Direction.IN))
        assertEquals(TextKey.NOTIF_TITLE_RECEIVED, NotificationCopy.titleKey(AlertState.SEEN, Direction.IN))
        assertEquals(TextKey.NOTIF_TITLE_SENT, NotificationCopy.titleKey(AlertState.SEEN, Direction.OUT))
        assertEquals("Settings push blocked", Templates.format(strings.get(TextKey.NOTIF_TITLE_BLOCKED), mapOf("what" to "Settings push")))

        // The words of the text match the actions (Allow and Block, or Open and Block).
        assertEquals(TextKey.NOTIF_HELD_ALLOW_FROM, NotificationCopy.heldKey(releasable = true, withSender = true))
        assertEquals(TextKey.NOTIF_HELD_ALLOW, NotificationCopy.heldKey(releasable = true, withSender = false))
        assertEquals(TextKey.NOTIF_HELD_OPEN_FROM, NotificationCopy.heldKey(releasable = false, withSender = true))
        assertEquals(TextKey.NOTIF_HELD_OPEN, NotificationCopy.heldKey(releasable = false, withSender = false))
        assertEquals("Allow it or block it.", strings.get(TextKey.NOTIF_HELD_ALLOW))
        assertEquals("From {who}. Allow it or block it.", strings.get(TextKey.NOTIF_HELD_ALLOW_FROM))
    }

    @Test
    fun `the action labels are the plain verbs of the plan`() {
        assertEquals("Open", strings.get(NotificationAction.OPEN.labelKey))
        assertEquals("Allow", strings.get(NotificationAction.ALLOW.labelKey))
        assertEquals("Block", strings.get(NotificationAction.BLOCK.labelKey))
        assertTrue(NotificationAction.OPEN.opensUi)
        assertFalse(NotificationAction.ALLOW.opensUi)
        assertFalse(NotificationAction.BLOCK.opensUi)
    }
}
