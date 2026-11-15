package dev.nightjar.app.domain.ingest

import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.domain.detect.DetectContext
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Transport
import dev.nightjar.contract.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A stored row gives back every fact the classifiers and detectors read. Synthetic data only. */
class StoredFactsTest {
    private fun row(classId: ClassId = ClassId.MO_DATA): MessageEntity =
        MessageEntity(
            eventId = "ev-1",
            sessionId = "s",
            revision = 2,
            direction = Direction.OUT.wire(),
            transport = Transport.MO_CS.wire(),
            source = 0,
            subId = 1,
            slot = 0,
            carrierName = "Test",
            classId = classId.wire(),
            severity = Severity.NOTABLE.wire(),
            detectorSeverity = null,
            severityOverridden = false,
            title = "t",
            originator = null,
            originatorNorm = null,
            smsc = null,
            destPort = 16000,
            contentType = null,
            textBody = null,
            textPreview = "",
            receivedAtWall = 1_000L,
            receivedAtElapsedNs = 1L,
            modemTs = null,
            ackResult = 0,
            verdict = Verdict.ALLOW.wire(),
            ruleId = null,
            fate = Fate.SENT.wire(),
            fateDetail = """{"errorCode":"7"}""",
            consumerPackage = "com.example.sender",
            callingPackage = "com.example.sender",
            persistFlag = false,
            concatRef = 9,
            partsTotal = 3,
            partsHave = 2,
            partsJson = null,
            purgeAt = null,
            isQuarantined = false,
            isReviewed = false,
            isStarred = false,
            isReplay = false,
            isSelfTest = false,
            needsProcessing = true,
            releasedFromId = null,
            buildFingerprint = null,
            payloadVersion = null,
            destinationNorm = "15559000000002",
            destination = "+1 555 900 0000 002",
            roaming = true,
            userUnlocked = false,
            releaseOf = "ev-0",
        )

    @Test
    fun `the stored columns reach the facts`() {
        val facts = row().toFacts()
        assertEquals("+1 555 900 0000 002", facts.destination)
        assertTrue(facts.roaming)
        assertFalse(facts.userUnlocked)
        assertEquals("com.example.sender", facts.consumerPackage)
        assertEquals("7", facts.fateDetail[EventFacts.FATE_DETAIL_ERROR_CODE])
        assertEquals(9, facts.concat?.ref)
        assertEquals(2, facts.concat?.have)
        assertEquals("ev-0", facts.flags.releaseOf)
        // A released copy is not a new message from the network.
        assertFalse(facts.fromNetwork)
    }

    @Test
    fun `a debug injection is flagged and is not network traffic`() {
        // The debugInjected column reaches EventFlags.debug.
        val debug = row().copy(releaseOf = null, debugInjected = true).toFacts()
        assertTrue(debug.flags.debug)
        assertFalse(debug.fromNetwork)
        assertFalse(
            row()
                .copy(releaseOf = null)
                .toFacts()
                .flags.debug,
        )
    }

    @Test
    fun `a row stored as FT-UNKNOWN reads as the true fate it shows`() {
        // Rows from older versions; row is a sent message.
        assertEquals(Fate.SEND_UNCONFIRMED, row().copy(fate = Fate.UNKNOWN.wire(), fateDetail = null).toFacts().fate)
        assertEquals(
            Fate.NOT_SENT,
            row().copy(fate = Fate.UNKNOWN.wire(), fateDetail = """{"noDispatch":true}""").toFacts().fate,
        )
        val received = row(ClassId.NORMAL).copy(direction = Direction.IN.wire(), fate = Fate.UNKNOWN.wire())
        assertEquals(Fate.ACCEPTED, received.copy(ackResult = 1).toFacts().fate)
        assertEquals(Fate.NOT_DISPATCHED, received.copy(ackResult = null).toFacts().fate)
        assertEquals(Fate.ACCEPTED, received.copy(ackResult = null, isReplay = true).toFacts().fate)
        assertEquals(Fate.REJECTED, received.copy(ackResult = 7).toFacts().fate)
    }

    @Test
    fun `an old row without the newer fact columns keeps the defaults`() {
        val facts = row().copy(destination = null, roaming = null, userUnlocked = null, releaseOf = null, fateDetail = null).toFacts()
        assertEquals("15559000000002", facts.destination)
        assertFalse(facts.roaming)
        assertTrue(facts.userUnlocked)
        assertNull(facts.flags.releaseOf)
        assertEquals("com.example.sender", facts.fateDetail[EventFacts.FATE_DETAIL_PACKAGE])
    }

    @Test
    fun `a CB row gives its header facts`() {
        val facts = row(ClassId.CB).copy(cbMessageId = 4370, cbSerial = 0x3001, partsHave = 1, partsTotal = 1).toFacts()
        assertEquals(4370, facts.cb?.messageId)
        assertEquals(0x3001, facts.cb?.serial)
        assertTrue(facts.cb?.emergency == true)
        assertNull(row().toFacts().cb)
    }

    @Test
    fun `the stored SIM OTA header gives the detector context`() {
        val withHeader = row(ClassId.SIM_DD).copy(simOtaHeader = """{"spi":5633,"tar":12345}""")
        assertEquals(5633, withHeader.detectContext().simOta?.spi)
        assertEquals(12345, withHeader.detectContext().simOta?.tar)
        // A response packet has no SPI: no facts, and the detectors keep their "unknown" path.
        assertEquals(DetectContext.NONE, row(ClassId.SIM_DD).copy(simOtaHeader = """{"tar":1}""").detectContext())
        assertEquals(DetectContext.NONE, row().detectContext())
    }

    @Test
    fun `the stored emergency call state gives the detector context`() {
        // A location request during an emergency call is expected.
        assertTrue(
            row(ClassId.WAP_SUPL)
                .copy(emergencyCallActive = true)
                .detectContext()
                .emergency.active,
        )
        assertFalse(
            row(ClassId.WAP_SUPL)
                .copy(emergencyCallActive = false)
                .detectContext()
                .emergency.active,
        )
        assertFalse(row(ClassId.WAP_SUPL).detectContext().emergency.active)
    }
}
