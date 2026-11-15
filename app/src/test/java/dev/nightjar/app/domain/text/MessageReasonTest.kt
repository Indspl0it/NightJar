package dev.nightjar.app.domain.text

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MessageReasonTest {
    private fun key(
        classId: ClassId,
        severity: Severity = Severity.SUSPICIOUS,
        direction: Direction = Direction.IN,
        fate: Fate = Fate.SYSTEM,
        undecodable: Boolean = false,
    ) = MessageReason.key(classId, severity, direction, fate, undecodable)

    @Test
    fun `normal and outgoing messages have no reason`() {
        assertNull(key(ClassId.SILENT, severity = Severity.INFO))
        assertNull(key(ClassId.SILENT, direction = Direction.OUT))
    }

    @Test
    fun `an undecodable push is malformed whatever its class`() {
        assertEquals(TextKey.REASON_MALFORMED, key(ClassId.WAP_OTHER, undecodable = true))
        assertEquals(TextKey.REASON_MALFORMED, key(ClassId.DATA_PORT, undecodable = true))
    }

    @Test
    fun `each hostile class says what it is trying to do`() {
        assertEquals(TextKey.REASON_SETTINGS_PUSH, key(ClassId.WAP_CP))
        assertEquals(TextKey.REASON_LOCATION, key(ClassId.WAP_SUPL))
        assertEquals(TextKey.REASON_SILENT, key(ClassId.SILENT))
        assertEquals(TextKey.REASON_FLASH, key(ClassId.FLASH))
    }

    @Test
    fun `a port message nobody received is explained by that, not by its class`() {
        assertEquals(TextKey.REASON_NOBODY_HANDLES, key(ClassId.DATA_PORT, fate = Fate.NO_RECEIVER))
        assertEquals(TextKey.REASON_HIDDEN_DATA, key(ClassId.DATA_PORT, fate = Fate.APP))
    }

    @Test
    fun `a rare format names what the message contains`() {
        // Each rare class has its own sentence. A Smart Messaging subtype names its content.
        assertEquals(TextKey.REASON_RARE_CLASS3, key(ClassId.CLASS3))
        assertEquals(TextKey.REASON_RARE_TELEMATIC, key(ClassId.TELEMATIC))
        assertEquals(TextKey.REASON_RARE_BINARY, key(ClassId.BINARY))
        assertEquals(TextKey.REASON_RARE_COMPRESSED, key(ClassId.COMPRESSED))
        assertEquals(TextKey.REASON_RARE_SMART, key(ClassId.SMART))
        assertEquals(
            TextKey.REASON_SMART_RINGTONE,
            MessageReason.key(ClassId.SMART, Severity.NOTABLE, Direction.IN, Fate.SYSTEM, titleKey = TextKey.TITLE_ST_SMART_RINGTONE),
        )
        // A subtype title of another class does not change the reason.
        assertEquals(
            TextKey.REASON_RARE_EMPTY,
            MessageReason.key(ClassId.EMPTY, Severity.NOTABLE, Direction.IN, Fate.SYSTEM, titleKey = TextKey.TITLE_ST_SMART_RINGTONE),
        )
        // Nothing received it: that still says more than the content.
        assertEquals(TextKey.REASON_NOBODY_HANDLES, key(ClassId.SMART, fate = Fate.NO_RECEIVER))
        val strings = MapStrings()
        assertEquals(TextKey.TITLE_ST_SMART_RINGTONE, MessageReason.smartTitleKey("Ringtone", strings))
        assertNull(MessageReason.smartTitleKey("Smart message", strings))
    }
}
