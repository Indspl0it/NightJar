package dev.nightjar.app.domain.text

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.contract.ClassId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Short list titles and list descriptions. Synthetic values only. */
class ListTitleTest {
    private val strings = MapStrings()

    /** Long but realistic placeholder values: a list title must stay short with them too. */
    private val args =
        mapOf(
            "port" to "65535",
            "anomaly" to "unknown message type",
            "contentType" to "application/vnd.wap.connectivity-wbxml",
            "channel" to "4370",
            "app" to "Example Messenger",
            "replaceType" to "7",
        )

    private fun rendered(key: TextKey): String = Templates.format(strings.get(key), args)

    private fun assertShort(
        classId: ClassId,
        key: TextKey,
    ) {
        val full = rendered(key)
        assertEquals(emptyList<String>(), Templates.unfilled(full), key.name)
        val short = ListTitle.title(classId, full, strings)
        assertTrue(short.isNotBlank(), "${key.name}: blank list title")
        assertTrue(short.length <= ListTitle.MAX_LENGTH, "${key.name}: \"$short\" has ${short.length} characters")
    }

    @Test
    fun `every class title and subtype title gets a short list title`() {
        var checked = 0
        for (id in ClassId.values()) {
            assertShort(id, ClassRegistry.of(id).titleKey)
            checked++
            for (subtype in ClassRegistry.subtypes(id)) {
                assertShort(id, subtype.titleKey)
                checked++
            }
        }
        assertTrue(checked > 80, "checked $checked titles")
    }

    @Test
    fun `every cell broadcast title gets a short list title`() {
        TextKey.entries.filter { it.name.startsWith("TITLE_CB_") }.forEach { assertShort(ClassId.CB, it) }
    }

    @Test
    fun `every short list title resource is short`() {
        TextKey.entries.filter { it.name.startsWith("TITLE_LIST_") }.forEach { key ->
            val text = rendered(key)
            assertTrue(text.isNotBlank() && text.length <= ListTitle.MAX_LENGTH, "${key.name}: \"$text\"")
        }
    }

    @Test
    fun `design titles`() {
        assertEquals("Malformed SMS", ListTitle.title(ClassId.MALFORMED_SMS, "Malformed SMS: unknown message type", strings))
        assertEquals("Binary message", ListTitle.title(ClassId.BINARY, "Binary message without a port", strings))
        assertEquals("Telematic message", ListTitle.title(ClassId.TELEMATIC, "Telematic message: voice", strings))
        assertEquals("Flash SMS", ListTitle.title(ClassId.FLASH, "Empty flash SMS (Class 0)", strings))
        assertEquals("Silent SMS", ListTitle.title(ClassId.SILENT, "Silent SMS (Type 0)", strings))
        assertEquals("WAP push", ListTitle.title(ClassId.DATA_PORT, "WAP connectionless session (secure)", strings))
        assertEquals("Voicemail indicator", ListTitle.title(ClassId.MWI, "Voicemail indicator", strings))
        assertEquals("Normal message", ListTitle.title(ClassId.NORMAL, "Normal message", strings))
        assertEquals("Data message sent", ListTitle.title(ClassId.MO_DATA, "Data message sent", strings))
    }

    @Test
    fun `a short title keeps the values it needs`() {
        assertEquals("Data to port 2948", ListTitle.title(ClassId.DATA_PORT, "Data message to port 2948", strings))
        assertEquals("Chat (RCS) setup", ListTitle.title(ClassId.RCS_PROV, "Chat (RCS) setup", strings))
    }

    @Test
    fun `a title from no known key gets the cut rule`() {
        assertEquals("Hidden data SMS", ListTitle.title(ClassId.DATA_PORT, "Hidden data SMS (port 2948)", strings))
        assertEquals("Something", ListTitle.cut("Something: else"))
        assertEquals("(odd)", ListTitle.cut("(odd)"))
    }

    @Test
    fun `design descriptions`() {
        assertEquals(
            "Unknown message type. A damaged header like this can crash a phone.",
            ListTitle.description(ClassId.MALFORMED_SMS, "Malformed SMS: unknown message type", TextKey.REASON_MALFORMED, strings),
        )
        assertEquals(
            "Data with no port. Most phones do not receive this type.",
            ListTitle.description(ClassId.BINARY, "Binary message without a port", TextKey.REASON_RARE_BINARY, strings),
        )
        assertEquals(
            "Voice service. Most phones do not receive this type.",
            ListTitle.description(ClassId.TELEMATIC, "Telematic message: voice", TextKey.REASON_RARE_TELEMATIC, strings),
        )
        assertEquals(
            "Empty. It shows on the screen, and Android does not save it.",
            ListTitle.description(ClassId.FLASH, "Empty flash SMS (Class 0)", TextKey.REASON_FLASH, strings),
        )
        assertEquals(
            "Secure session for an app. It has no text.",
            ListTitle.description(ClassId.DATA_PORT, "WAP connectionless session (secure)", TextKey.REASON_HIDDEN_DATA, strings),
        )
        assertEquals(
            strings.get(TextKey.REASON_SILENT),
            ListTitle.description(ClassId.SILENT, "Silent SMS (Type 0)", TextKey.REASON_SILENT, strings),
        )
    }

    @Test
    fun `nothing receives it wins over the list description`() {
        val text = ListTitle.description(ClassId.BINARY, "Binary message without a port", TextKey.REASON_NOBODY_HANDLES, strings)
        assertEquals(strings.get(TextKey.REASON_NOBODY_HANDLES), text)
    }

    @Test
    fun `dropped subtype words and the app start the description`() {
        assertEquals(
            "Changes files on the SIM. " + strings.get(TextKey.REASON_SIM),
            ListTitle.description(ClassId.SIM_DD, "SIM update from the network: changes files on the SIM", TextKey.REASON_SIM, strings),
        )
        assertEquals(
            "Example sent this message.",
            ListTitle.description(ClassId.MO_VISIBLE, "Message sent by an app (Example)", null, strings),
        )
        assertEquals(
            "Example added this message. " + strings.get(TextKey.REASON_INJECTED),
            ListTitle.description(ClassId.INJECTED, "Message added by an app (Example)", TextKey.REASON_INJECTED, strings),
        )
    }

    @Test
    fun `a normal row has no description`() {
        assertNull(ListTitle.description(ClassId.NORMAL, "Normal message", null, strings))
    }
}
