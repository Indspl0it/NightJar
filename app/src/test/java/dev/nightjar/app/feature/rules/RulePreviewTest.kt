package dev.nightjar.app.feature.rules

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.StringMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The live preview decodes a pasted TPDU with:decoder and runs the same rule engine. */
class RulePreviewTest {
    // :decoder fixture `deliver_7bit.hex`: SMSC 1234567890, alphanumeric sender NIGHTJAR, text "Hello".
    private val deliver = "06912143658709040ED0CEE411495506A500006211502143652205C8329BFD06"

    private companion object {
        /** A carriage return, a line feed and a tab: what a paste from a Windows file or a terminal adds. */
        const val CRLF_TAB = "\r\n\t"
    }

    @Test
    fun `a matching sender previews as a match`() {
        val draft =
            RuleDraft(
                classes = setOf(ClassId.WAP_CP),
                fromEnabled = true,
                fromOp = StringMatch.Op.IS,
                fromValue = "NIGHTJAR",
                action = Verdict.QUARANTINE,
            )
        val result = previewRule(draft, deliver)
        val decoded = assertInstanceOf(RulePreview.Decoded::class.java, result)
        assertEquals("NIGHTJAR", decoded.originator)
        assertEquals("1234567890", decoded.smsc)
        assertTrue(decoded.matched)
        assertEquals(ClassId.WAP_CP, decoded.matchedClass)
        assertEquals(Verdict.QUARANTINE, decoded.action)
    }

    @Test
    fun `a different sender previews as no match`() {
        val draft =
            RuleDraft(
                classes = setOf(ClassId.WAP_CP),
                fromEnabled = true,
                fromValue = "SOMEONE ELSE",
            )
        val decoded = assertInstanceOf(RulePreview.Decoded::class.java, previewRule(draft, deliver))
        assertFalse(decoded.matched)
    }

    @Test
    fun `a text condition is evaluated against the decoded body`() {
        val draft =
            RuleDraft(
                classes = setOf(ClassId.DATA_PORT),
                conditions = listOf(DraftCondition.Text(value = "hello")),
                action = Verdict.ALLOW,
            )
        assertTrue(assertInstanceOf(RulePreview.Decoded::class.java, previewRule(draft, deliver)).matched)
    }

    @Test
    fun `an empty field is not previewed`() {
        assertEquals(RulePreview.Empty, previewRule(RuleDraft(), "   "))
    }

    @Test
    fun `malformed hex reports the decoder error`() {
        val result = previewRule(RuleDraft(classes = setOf(ClassId.WAP_CP)), "ZZ")
        val invalid = assertInstanceOf(RulePreview.Invalid::class.java, result)
        assertEquals(RulePreview.InvalidReason.MALFORMED, invalid.reason)
    }

    @Test
    fun `a paste with CR LF line ends and tabs decodes like the plain hex`() {
        val draft = RuleDraft(classes = setOf(ClassId.WAP_CP), fromEnabled = true, fromValue = "NIGHTJAR")
        val pasted = deliver.chunked(16).joinToString(separator = CRLF_TAB)
        assertEquals(previewRule(draft, deliver), previewRule(draft, pasted))
    }

    @Test
    fun `a clamped action shows what the engine does, not what the rule asks`() {
        // CL-SILENT never holds, so the engine clamps the rule's Hold to Allow.
        val draft =
            RuleDraft(
                classes = setOf(ClassId.SILENT),
                fromEnabled = true,
                fromValue = "NIGHTJAR",
                action = Verdict.QUARANTINE,
            )
        val decoded = assertInstanceOf(RulePreview.Decoded::class.java, previewRule(draft, deliver))
        assertTrue(decoded.matched)
        assertEquals(Verdict.ALLOW, decoded.action)
        assertTrue(decoded.clamped)
    }
}
