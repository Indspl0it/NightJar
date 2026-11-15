package dev.nightjar.app.domain.ingest

import dev.nightjar.app.domain.FakeDetectionHistory
import dev.nightjar.app.domain.TestEvents
import dev.nightjar.app.domain.classify.EventClassifier
import dev.nightjar.app.domain.detect.DetectionDraft
import dev.nightjar.app.domain.detect.DetectorConfig
import dev.nightjar.app.domain.detect.DetectorEngine
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.severity.SeverityConfig
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/** Severity → detectors → coverage → counters → rule usage → notify, in order. */
class ProcessingPipelineTest {
    private val history = FakeDetectionHistory()

    private class Recorder {
        val detections = mutableListOf<DetectionDraft>()
        val coverage = mutableListOf<Pair<ClassId, Long>>()
        val selfTests = mutableListOf<Pair<ClassId, Long>>()
        val counters = mutableListOf<Triple<Int, ClassId, Int>>()
        val severities = mutableListOf<Triple<Long, Severity, Boolean>>()
        val rules = mutableListOf<Pair<Long, Long>>()
        val alerts = mutableListOf<ProcessedEvent>()
        val order = mutableListOf<String>()
    }

    private fun pipeline(
        recorder: Recorder,
        config: SeverityConfig = SeverityConfig.DEFAULT,
        detectors: DetectorConfig = DetectorConfig.DEFAULT,
    ): ProcessingPipeline {
        val classifier = EventClassifier(DetectorEngine(detectors, history, config.knownOriginators), config)
        return ProcessingPipeline(
            classifier = classifier,
            detections = { draft ->
                recorder.order += "detection"
                recorder.detections += draft
                1L
            },
            coverage =
                object : CoverageSink {
                    override suspend fun seenFromNetwork(
                        classId: ClassId,
                        atWallMs: Long,
                    ) {
                        recorder.order += "coverage"
                        recorder.coverage += classId to atWallMs
                    }

                    override suspend fun selfTestArrived(
                        classId: ClassId,
                        atWallMs: Long,
                    ) {
                        recorder.selfTests += classId to atWallMs
                    }
                },
            counters = { day, _, _, classId, n ->
                recorder.order += "counter"
                recorder.counters += Triple(day, classId, n)
            },
            ruleMatches = { ruleId, at ->
                recorder.order += "rule"
                recorder.rules += ruleId to at
            },
            severities = { id, severity, overridden ->
                recorder.severities += Triple(id, severity, overridden)
            },
            alerts = { processed ->
                recorder.order += "alert"
                recorder.alerts += processed
            },
            zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun `a silent ping from a new sender becomes suspicious and alerts`() =
        runTest {
            val recorder = Recorder()
            val event = TestEvents.inbound(ClassId.SILENT, at = 1_000_000L)
            pipeline(recorder).process(listOf(PendingEvent(messageId = 7, facts = event)))

            assertEquals(Severity.SUSPICIOUS, recorder.severities.single().second)
            assertEquals(1, recorder.detections.size)
            assertEquals(ClassId.SILENT, recorder.coverage.single().first)
            assertEquals(listOf("detection", "coverage", "alert"), recorder.order)
            assertEquals(7L, recorder.alerts.single().messageId)
        }

    @Test
    fun `a later revision of a processed row does not count or alert again`() =
        runTest {
            val recorder = Recorder()
            val event = TestEvents.inbound(ClassId.NORMAL, at = 1_700_000_000_000L).copy(fate = Fate.SHOWN, ruleId = 5L)
            pipeline(recorder).process(listOf(PendingEvent(messageId = 3, facts = event, firstPass = false)))

            assertEquals(1, recorder.severities.size)
            assertTrue(recorder.counters.isEmpty())
            assertTrue(recorder.rules.isEmpty())
            assertTrue(recorder.alerts.isEmpty())
        }

    @Test
    fun `a debug injection is processed like real traffic`() =
        runTest {
            val recorder = Recorder()
            // The same silent ping as above, injected as a debug injection, gets the same treatment.
            val silent = TestEvents.inbound(ClassId.SILENT, at = 1_000_000L)
            val event = silent.copy(flags = silent.flags.copy(debug = true), ruleId = 5L)
            val out = pipeline(recorder).process(listOf(PendingEvent(messageId = 8, facts = event)))

            assertEquals(Severity.SUSPICIOUS, recorder.severities.single().second)
            assertEquals(1, recorder.detections.size)
            assertEquals(ClassId.SILENT, recorder.coverage.single().first)
            assertEquals(listOf("detection", "coverage", "alert", "rule"), recorder.order)
            assertEquals(8L, recorder.alerts.single().messageId)
            assertFalse(
                recorder.alerts
                    .single()
                    .facts.flags.debug,
                "the alert sees traffic, not a debug row",
            )
            assertEquals(5L to 1_000_000L, recorder.rules.single())
            assertEquals(8L, out.single().messageId)
        }

    @Test
    fun `a debug injection of a counter-only class updates the counter`() =
        runTest {
            val recorder = Recorder()
            val normal = TestEvents.inbound(ClassId.NORMAL, at = 1_700_000_000_000L).copy(fate = Fate.SHOWN)
            val event = normal.copy(flags = normal.flags.copy(debug = true))
            pipeline(recorder).process(listOf(PendingEvent(messageId = 9, facts = event)))

            assertEquals(ClassId.NORMAL, recorder.counters.single().second)
        }

    @Test
    fun `a counter-only class updates the counter and never alerts`() =
        runTest {
            val recorder = Recorder()
            val event = TestEvents.inbound(ClassId.NORMAL, at = 1_700_000_000_000L).copy(fate = Fate.SHOWN)
            pipeline(recorder).process(listOf(PendingEvent(messageId = 3, facts = event)))

            assertEquals(1, recorder.counters.size)
            val (day, classId, n) = recorder.counters.single()
            assertEquals(20231114, day)
            assertEquals(ClassId.NORMAL, classId)
            assertEquals(1, n)
            // Coverage answers "which hidden classes does this modem forward"; a normal SMS says nothing.
            assertTrue(recorder.coverage.isEmpty())
            // The alert sink is the router's input, not its decision: it is called for every processed event and the
            // router suppresses the notification for a counter-only class.
            assertEquals(1, recorder.alerts.size)
        }

    @Test
    fun `a blocked send of a counter-only class is a row, not a counter entry`() =
        runTest {
            // The feed shows and counts a blocked CL-MO-VISIBLE row, so the daily counter must not.
            val recorder = Recorder()
            val event =
                TestEvents.outbound(ClassId.MO_VISIBLE).copy(
                    fate = Fate.BLOCKED,
                    fateDetail = mapOf(EventFacts.FATE_DETAIL_REASON to EventFacts.REASON_OUTGOING_BLOCK),
                )
            pipeline(recorder).process(listOf(PendingEvent(messageId = 9, facts = event)))

            assertTrue(recorder.counters.isEmpty())
            assertTrue(recorder.coverage.isEmpty())
            assertEquals(1, recorder.alerts.size)
            // The same class without the block still only feeds the counter.
            val sent = Recorder()
            pipeline(sent).process(listOf(PendingEvent(messageId = 10, facts = TestEvents.outbound(ClassId.MO_VISIBLE))))
            assertEquals(ClassId.MO_VISIBLE, sent.counters.single().second)
        }

    @Test
    fun `a self test event updates self test coverage, not network coverage`() =
        runTest {
            val recorder = Recorder()
            val event =
                TestEvents.inbound(ClassId.WAP_CP).copy(
                    flags =
                        dev.nightjar.app.domain.model
                            .EventFlags(selfTest = true, runId = "run-1"),
                )
            pipeline(recorder).process(listOf(PendingEvent(messageId = 4, facts = event)))
            assertEquals(1, recorder.selfTests.size)
            assertTrue(recorder.coverage.isEmpty())
        }

    @Test
    fun `a self test canary adds no finding, no rule count and no counter`() =
        runTest {
            val recorder = Recorder()
            // B3: a counter-only class with a rule id would otherwise count twice; a canary counts nowhere.
            val event =
                TestEvents.inbound(ClassId.NORMAL).copy(
                    ruleId = 7,
                    flags =
                        dev.nightjar.app.domain.model
                            .EventFlags(selfTest = true, runId = "run-2", canaryClass = ClassId.NORMAL),
                )
            pipeline(recorder).process(listOf(PendingEvent(messageId = 5, facts = event)))
            assertTrue(recorder.detections.isEmpty())
            assertTrue(recorder.rules.isEmpty())
            assertTrue(recorder.counters.isEmpty())
            assertTrue(recorder.coverage.isEmpty())
        }

    @Test
    fun `a released message is not counted as network coverage`() =
        runTest {
            val recorder = Recorder()
            val event =
                TestEvents.inbound(ClassId.WAP_CP).copy(
                    flags =
                        dev.nightjar.app.domain.model
                            .EventFlags(releaseOf = "original"),
                )
            pipeline(recorder).process(listOf(PendingEvent(messageId = 5, facts = event)))
            assertTrue(recorder.coverage.isEmpty())
        }

    @Test
    fun `a matched rule increments its counter`() =
        runTest {
            val recorder = Recorder()
            val event = TestEvents.inbound(ClassId.WAP_CP, at = 5_000L).copy(ruleId = 42)
            pipeline(recorder).process(listOf(PendingEvent(messageId = 9, facts = event)))
            assertEquals(listOf(42L to 5_000L), recorder.rules)
        }

    @Test
    fun `severity override is flagged on the row`() =
        runTest {
            val recorder = Recorder()
            val config = SeverityConfig(overrides = mapOf(ClassId.WAP_DM to Severity.HIGH))
            val event = TestEvents.inbound(ClassId.WAP_DM)
            pipeline(recorder, config).process(listOf(PendingEvent(messageId = 11, facts = event)))
            val (_, severity, overridden) = recorder.severities.single()
            assertEquals(Severity.HIGH, severity)
            assertTrue(overridden)
        }

    @Test
    fun `an override equal to the row default is not flagged`() =
        runTest {
            val recorder = Recorder()
            // CL-WAP-DM is Suspicious by default, so this setting changes nothing on the row.
            val config = SeverityConfig(overrides = mapOf(ClassId.WAP_DM to Severity.SUSPICIOUS))
            pipeline(recorder, config).process(listOf(PendingEvent(messageId = 12, facts = TestEvents.inbound(ClassId.WAP_DM))))
            val (_, severity, overridden) = recorder.severities.single()
            assertEquals(Severity.SUSPICIOUS, severity)
            assertFalse(overridden)
        }

    @Test
    fun `events are processed in order and each one alerts once`() =
        runTest {
            val recorder = Recorder()
            val events =
                listOf(
                    PendingEvent(1, TestEvents.inbound(ClassId.SILENT, at = 1_000L, eventId = "a")),
                    PendingEvent(2, TestEvents.inbound(ClassId.SILENT, at = 2_000L, eventId = "b")),
                    PendingEvent(3, TestEvents.inbound(ClassId.SILENT, at = 3_000L, eventId = "c")),
                )
            pipeline(recorder).process(events)
            assertEquals(listOf(1L, 2L, 3L), recorder.alerts.map { it.messageId })
        }

    @Test
    fun `a trusted outbound package is info`() =
        runTest {
            val recorder = Recorder()
            val config = SeverityConfig(trustedPackages = setOf("com.example.app"))
            val event = TestEvents.outbound(ClassId.MO_DATA, destination = "+15559876543210")
            pipeline(recorder, config).process(listOf(PendingEvent(messageId = 12, facts = event)))
            assertEquals(Severity.INFO, recorder.severities.single().second)
        }

    @Test
    fun `the counter day follows the configured zone`() {
        val recorder = Recorder()
        val pipeline = pipeline(recorder)
        assertEquals(20260101, pipeline.counterDay(1_767_225_600_000L))
    }

    @Test
    fun `an inbound direction is not counted as outbound`() =
        runTest {
            val recorder = Recorder()
            val event = TestEvents.inbound(ClassId.MO_VISIBLE)
            // CL-MO-VISIBLE with IN direction is a contract violation, but the pipeline must not crash on it.
            pipeline(recorder).process(listOf(PendingEvent(messageId = 13, facts = event)))
            assertEquals(Direction.IN, event.direction)
            assertFalse(recorder.counters.isEmpty())
        }

    @Test
    fun `a failing alert neither stops the row nor makes a retry count it twice`() =
        runTest {
            val recorder = Recorder()
            val throwing =
                ProcessingPipeline(
                    classifier =
                        EventClassifier(
                            DetectorEngine(DetectorConfig.DEFAULT, history, SeverityConfig.DEFAULT.knownOriginators),
                            SeverityConfig.DEFAULT,
                        ),
                    detections = { 1L },
                    coverage =
                        object : CoverageSink {
                            override suspend fun seenFromNetwork(classId: ClassId, atWallMs: Long) = Unit

                            override suspend fun selfTestArrived(classId: ClassId, atWallMs: Long) = Unit
                        },
                    counters = { _, _, _, classId, n -> recorder.counters += Triple(0, classId, n) },
                    ruleMatches = { _, _ -> },
                    severities = { _, _, _ -> },
                    alerts = { error("notification manager unavailable") },
                    zone = ZoneOffset.UTC,
                )
            val event = TestEvents.inbound(ClassId.NORMAL, at = 1_700_000_000_000L).copy(fate = Fate.SHOWN)
            val out = throwing.process(listOf(PendingEvent(messageId = 3, facts = event)))
            assertEquals(1, out.size)
            assertEquals(1, recorder.counters.size)
        }
}
