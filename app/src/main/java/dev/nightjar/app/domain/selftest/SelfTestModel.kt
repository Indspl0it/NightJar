package dev.nightjar.app.domain.selftest

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import dev.nightjar.contract.TraceStage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The eight canaries of Appendix F.
 *
 * The order is the appendix order, so the picker and the stored report are stable across runs. [requiredHooks] is the
 * safety precondition the payload checks before injecting; the app repeats the check against the control
 * binder's hook health so a skipped canary is reported as `SKIPPED:<hook>` instead of waiting five seconds for an event
 * that will never arrive.
 */
enum class CanaryClass(
    val classId: ClassId,
    val requiredHooks: List<String>,
    /** Trace stage wire name ([TraceStage]) that proves the canary reached its interception point (Appendix F "terminal stage"). */
    val expectedStage: String,
    /** Marker inside that stage's detail; null when the stage alone is enough. */
    val expectedDetail: String?,
    val expectedFate: Fate,
) {
    FLASH(ClassId.FLASH, listOf("HK-08"), TraceStage.FW_HEAD_FILTER.wire(), "CANARY", Fate.BLOCKED),
    DATA_PORT(ClassId.DATA_PORT, listOf("HK-08"), TraceStage.FW_HEAD_FILTER.wire(), "CANARY", Fate.BLOCKED),
    WAP_SI(ClassId.WAP_SISL, listOf("HK-14"), TraceStage.WAP_DISPATCH.wire(), "result=1", Fate.BLOCKED),
    WAP_CP(ClassId.WAP_CP, listOf("HK-14"), TraceStage.WAP_DISPATCH.wire(), "result=1", Fate.BLOCKED),
    MWI(ClassId.MWI, listOf("HK-23"), TraceStage.MWI_INDICATOR.wire(), "suppressed", Fate.SYSTEM),
    SILENT(ClassId.SILENT, listOf("HK-06", "HK-07"), TraceStage.HANDLER_RADIO_SPECIFIC.wire(), "branch=type0", Fate.SYSTEM),
    SIM_DD(ClassId.SIM_DD, listOf("HK-15"), TraceStage.USIM_DOWNLOAD.wire(), "CANARY", Fate.BLOCKED),
    REPLACE(ClassId.REPLACE, listOf("HK-08"), TraceStage.FW_HEAD_FILTER.wire(), "CANARY", Fate.BLOCKED),
    ;

    /** The wire name used in `classesJson` (`FLASH`, `WAP_SI`, …), exactly as the payload parses it. */
    val wire: String get() = name

    companion object {
        /** Appendix F order, used by the picker and the report. */
        val order: List<CanaryClass> = entries.toList()

        fun of(classId: ClassId): CanaryClass? = entries.firstOrNull { it.classId == classId }

        /** Case-insensitive, accepts `CL-` prefixes and `-`/space for `_` (the payload is as forgiving). */
        fun parse(name: String?): CanaryClass? {
            if (name == null) return null
            val normalized =
                name
                    .trim()
                    .uppercase()
                    .removePrefix("CL-")
                    .replace('-', '_')
                    .replace(' ', '_')
            return entries.firstOrNull { it.name == normalized }
        }
    }
}

/** Outcome of one canary in a run (03 result chips). */
enum class SelfTestStatus {
    PASS,
    FAIL,

    /** A safety precondition was not met; the payload never injected it. */
    SKIPPED,

    /** Injected, but no matching event arrived within five seconds. */
    TIMEOUT,

    /** The user cancelled before this canary was evaluated. */
    CANCELLED,
}

/** One canary's result. [reason] is `SKIPPED:<hook>` for skips and a `CLASS:`/`STAGE:`/`FATE:` marker for failures. */
data class ClassResult(
    val canary: CanaryClass,
    val status: SelfTestStatus,
    val reason: String? = null,
)

/** One trace step of an ingested event, reduced to what the evaluation reads. */
data class TraceStepView(
    val stage: String,
    val detail: String?,
)

/**
 * A self-test event as it reached the app.
 *
 * Produced at the IPC ingest boundary from `flags.selfTest`/`runId`/`canaryClass` and the stored trace. It is
 * deliberately a domain type: the evaluator runs on the JVM and never touches Room or the binder.
 */
data class SelfTestObservation(
    val runId: String,
    val canaryClass: ClassId,
    /** The final class the payload assigned. */
    val classId: ClassId,
    val fate: String,
    val trace: List<TraceStepView>,
    val receivedAtWallMs: Long,
)

/** The complete result of one run, as stored in `self_test_run.resultsJson`. */
data class SelfTestOutcome(
    val runId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val cancelled: Boolean,
    val results: List<ClassResult>,
) {
    val passed: Int get() = results.count { it.status == SelfTestStatus.PASS }

    val failed: Int get() = results.count { it.status == SelfTestStatus.FAIL || it.status == SelfTestStatus.TIMEOUT }

    /** Every canary that ran passed; a skipped canary keeps this false (needs a full pass). */
    val everyCanaryPassed: Boolean
        get() = results.size == CanaryClass.order.size && results.all { it.status == SelfTestStatus.PASS }

    fun resultFor(canary: CanaryClass): ClassResult? = results.firstOrNull { it.canary == canary }
}

/** Encoding and evaluation of a run. */
object SelfTestResults {
    const val SCHEMA = 1

    /** Results are stored as one JSON object; unknown keys are ignored on read (lerance). */
    fun encode(outcome: SelfTestOutcome): String =
        JSONObject()
            .put("schema", SCHEMA)
            .put("runId", outcome.runId)
            .put("startedAt", outcome.startedAt)
            .put("finishedAt", outcome.finishedAt)
            .put("cancelled", outcome.cancelled)
            .put(
                "results",
                JSONArray(
                    outcome.results.map { result ->
                        JSONObject()
                            .put("canary", result.canary.wire)
                            .put("class", result.canary.classId.wire())
                            .put("status", result.status.name)
                            .put("reason", result.reason)
                    },
                ),
            ).toString()

    /** Tolerant decode: a malformed or older document yields null rather than failing the screen. */
    fun decode(json: String?): SelfTestOutcome? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            // A present but non-array "results" is a corrupt document, not an empty one.
            val raw = root.opt("results")
            if (raw != null && raw !is JSONArray) return null
            val array = raw as? JSONArray ?: JSONArray()
            val results = ArrayList<ClassResult>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val canary = CanaryClass.parse(item.optString("canary", null)) ?: continue
                val status =
                    SelfTestStatus.entries.firstOrNull { it.name == item.optString("status", "") } ?: continue
                results += ClassResult(canary, status, item.optString("reason", "").ifEmpty { null })
            }
            SelfTestOutcome(
                runId = root.optString("runId", ""),
                startedAt = root.optLong("startedAt", 0),
                finishedAt = root.optLong("finishedAt", 0),
                cancelled = root.optBoolean("cancelled", false),
                results = results,
            )
        }.getOrNull()
    }

    /**
     * Captured (a non-null observation), final class correct, expected terminal stage reached, expected fate.
     *
     * A null observation is a timeout, not a failure: the payload may not have injected the canary at all.
     */
    fun evaluate(
        canary: CanaryClass,
        observation: SelfTestObservation?,
    ): ClassResult {
        if (observation == null) return ClassResult(canary, SelfTestStatus.TIMEOUT)
        val stageReached =
            observation.trace.any { step ->
                step.stage == canary.expectedStage &&
                    (canary.expectedDetail == null || step.detail?.contains(canary.expectedDetail) == true)
            }
        return when {
            observation.classId != canary.classId -> {
                ClassResult(canary, SelfTestStatus.FAIL, "CLASS:${observation.classId.wire()}")
            }

            !stageReached -> {
                ClassResult(canary, SelfTestStatus.FAIL, "STAGE:${canary.expectedStage}")
            }

            observation.fate != canary.expectedFate.wire() -> {
                ClassResult(canary, SelfTestStatus.FAIL, "FATE:${observation.fate}")
            }

            else -> {
                ClassResult(canary, SelfTestStatus.PASS)
            }
        }
    }

    /** The `classesJson` the control binder expects: a JSON array of Appendix F names. */
    fun classesJson(classes: Collection<CanaryClass>): String =
        JSONArray(classes.sortedBy { it.ordinal }.map { it.wire }).toString()
}
