package dev.nightjar.app.domain.selftest

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Captured, final class and terminal stage, plus the `resultsJson` round trip. */
class SelfTestResultsTest {
    private fun observation(
        canary: CanaryClass,
        classId: ClassId = canary.classId,
        fate: Fate = canary.expectedFate,
        stage: String = canary.expectedStage,
        detail: String? = canary.expectedDetail?.let { "ct=x $it" },
    ) = SelfTestObservation(
        runId = "run-1",
        canaryClass = canary.classId,
        classId = classId,
        fate = fate.wire(),
        trace = listOf(TraceStepView("handler.dispatchMessage", "source=2"), TraceStepView(stage, detail)),
        receivedAtWallMs = 1_000,
    )

    @Test
    fun `a matching event passes`() {
        for (canary in CanaryClass.order) {
            assertEquals(SelfTestStatus.PASS, SelfTestResults.evaluate(canary, observation(canary)).status)
        }
    }

    @Test
    fun `a missing event is a timeout`() {
        val result = SelfTestResults.evaluate(CanaryClass.FLASH, null)
        assertEquals(SelfTestStatus.TIMEOUT, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `a wrong final class fails with the class in the reason`() {
        val result = SelfTestResults.evaluate(CanaryClass.FLASH, observation(CanaryClass.FLASH, classId = ClassId.WAP_CP))
        assertEquals(SelfTestStatus.FAIL, result.status)
        assertEquals("CLASS:CL-WAP-CP", result.reason)
    }

    @Test
    fun `a missing terminal stage fails with the stage in the reason`() {
        val result =
            SelfTestResults.evaluate(
                CanaryClass.WAP_SI,
                observation(CanaryClass.WAP_SI, stage = "handler.dispatchMessage"),
            )
        assertEquals(SelfTestStatus.FAIL, result.status)
        assertEquals("STAGE:wap.dispatch", result.reason)
    }

    @Test
    fun `a stage without the expected marker is not enough`() {
        val result =
            SelfTestResults.evaluate(
                CanaryClass.MWI,
                observation(CanaryClass.MWI, detail = "applied"),
            )
        assertEquals(SelfTestStatus.FAIL, result.status)
        assertEquals("STAGE:mwi.indicator", result.reason)
    }

    @Test
    fun `a wrong fate fails`() {
        val result =
            SelfTestResults.evaluate(
                CanaryClass.SILENT,
                observation(CanaryClass.SILENT, fate = Fate.SHOWN),
            )
        assertEquals(SelfTestStatus.FAIL, result.status)
        assertEquals("FATE:FT-SHOWN", result.reason)
    }

    @Test
    fun `results json round trips every status`() {
        val outcome =
            SelfTestOutcome(
                runId = "run-7",
                startedAt = 10,
                finishedAt = 20,
                cancelled = false,
                results =
                    listOf(
                        ClassResult(CanaryClass.FLASH, SelfTestStatus.PASS),
                        ClassResult(CanaryClass.DATA_PORT, SelfTestStatus.FAIL, "CLASS:CL-NORMAL"),
                        ClassResult(CanaryClass.WAP_SI, SelfTestStatus.SKIPPED, "SKIPPED:HK-14"),
                        ClassResult(CanaryClass.MWI, SelfTestStatus.TIMEOUT),
                        ClassResult(CanaryClass.SILENT, SelfTestStatus.CANCELLED),
                    ),
            )
        val decoded = SelfTestResults.decode(SelfTestResults.encode(outcome))
        assertEquals(outcome, decoded)
    }

    @Test
    fun `a malformed or empty document decodes to null`() {
        assertNull(SelfTestResults.decode(null))
        assertNull(SelfTestResults.decode(""))
        assertNull(SelfTestResults.decode("not json"))
        assertNull(SelfTestResults.decode("""{"results":"nope"}"""))
    }

    @Test
    fun `a full pass needs all eight canaries`() {
        val full =
            SelfTestOutcome(
                "r",
                0,
                0,
                false,
                CanaryClass.order.map { ClassResult(it, SelfTestStatus.PASS) },
            )
        assertTrue(full.everyCanaryPassed)

        val skipped =
            SelfTestOutcome(
                "r",
                0,
                0,
                false,
                CanaryClass.order.map {
                    if (it ==
                        CanaryClass.MWI
                    ) {
                        ClassResult(it, SelfTestStatus.SKIPPED, "SKIPPED:HK-23")
                    } else {
                        ClassResult(it, SelfTestStatus.PASS)
                    }
                },
            )
        assertFalse(skipped.everyCanaryPassed)

        val partial = SelfTestOutcome("r", 0, 0, false, listOf(ClassResult(CanaryClass.FLASH, SelfTestStatus.PASS)))
        assertFalse(partial.everyCanaryPassed)
    }

    @Test
    fun `classes json is appendix order and uses the payload wire names`() {
        assertEquals(
            """["FLASH","WAP_SI","SIM_DD"]""",
            SelfTestResults.classesJson(listOf(CanaryClass.SIM_DD, CanaryClass.WAP_SI, CanaryClass.FLASH)),
        )
    }

    @Test
    fun `canary names parse case-insensitively with optional class prefix`() {
        assertEquals(CanaryClass.WAP_CP, CanaryClass.parse("cl-wap-cp"))
        assertEquals(CanaryClass.SIM_DD, CanaryClass.parse(" sim-dd "))
        assertEquals(CanaryClass.SILENT, CanaryClass.parse("Silent"))
        assertNull(CanaryClass.parse("NOPE"))
        assertNull(CanaryClass.parse(null))
    }
}
