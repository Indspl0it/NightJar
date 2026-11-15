package dev.nightjar.app.feature.rules

import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.PreviewRow
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Verdict
import dev.nightjar.contract.Wire
import dev.nightjar.rules.ClampReason
import dev.nightjar.rules.Decision
import dev.nightjar.rules.Guards
import dev.nightjar.rules.MatchInput
import dev.nightjar.rules.RuleEngine
import dev.nightjar.rules.TextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One stored message the draft would have matched (04). */
data class PreviewMatch(
    val messageId: Long,
    val classId: ClassId,
    val receivedAtWall: Long,
    val originator: String?,
    /** Final verdict after the five clamps: what the payload would have done with this row. */
    val action: Verdict,
    val clampedFrom: Verdict? = null,
    val clampReason: ClampReason? = null,
)

/** One preview run over the stored messages of the last [windowDays] days. */
data class PreviewResult(
    val windowDays: Int,
    /** Rows the SQL pre-filter handed to the evaluator. */
    val scanned: Int,
    val matches: List<PreviewMatch>,
    /** Wall time of the whole preview (query plus evaluation), against the preview time budget. */
    val elapsedMs: Long,
) {
    /** "Would have matched n messages in the last 30 days". */
    val count: Int get() = matches.size

    /** Matches the engine clamped to ALLOW, e.g. by safe mode or the enforcement matrix (`EnforcementMatrix`). */
    val clamped: Int get() = matches.count { it.clampReason != null }
}

/**
 * How many stored messages a draft rule would have matched.
 *
 * Two steps, in this order:
 * 1. the SQL pre-filter narrows `message` to the last 30 days, the draft's classes and its SIM slot
 *    ([MessageDao.previewCandidates]);
 * 2. the shared [RuleEngine] — the same compiled evaluator the payload runs — evaluates every remaining row.
 *
 * Nothing is guessed: class, sender, SMSC, port, content type, text, subtype, cell broadcast id and roaming come from
 * the stored row, and the guards are nominal so the result shows what the rule itself decides. The SIM slot
 * is part of the pre-filter. The cell broadcast id sets the emergency flag, so an emergency alert shows the G-01
 * emergency clamp as the payload applies it. A row that has no roaming value (stored before, or not reported) counts as not
 * roaming, which is the default of the payload's [MatchInput]. The draft is evaluated as enabled — the preview
 * describes the rule's effect, and the on/off toggle is a separate control.
 *
 * The engine state ([MatchInput], [Decision], the text cell) is allocated once per run; only the rows the query
 * returns and the matches are objects. The loop runs on [Dispatchers.Default] and stops as soon as the caller cancels
 * (the editor cancels on every keystroke).
 */
@Singleton
class PreviewMatcher
    @Inject
    constructor(
        private val messages: MessageDao,
        private val clock: Clock,
    ) {
        suspend fun preview(draft: RuleDraft): PreviewResult {
            val startedNs = System.nanoTime()
            val classes = draft.classes.map { it.wire() }.sorted()
            val rows = messages.previewCandidates(clock.now() - WINDOW_DAYS * DAY_MS, classes.size, classes, draft.slot)
            return withContext(Dispatchers.Default) { evaluate(draft, rows, startedNs) }
        }

        private suspend fun evaluate(
            draft: RuleDraft,
            rows: List<PreviewRow>,
            startedNs: Long,
        ): PreviewResult {
            val engine = RuleEngine.compile(documentOf(draft.copy(enabled = true)))
            val guards = Guards.NOMINAL
            val input = MatchInput()
            val decision = Decision()
            val text = TextCell()
            val textProvider = TextProvider { text.value }
            val matches = ArrayList<PreviewMatch>()
            for (row in rows) {
                currentCoroutineContext().ensureActive()
                val classId = Wire.parse(ClassId::class.java, row.classId, null) ?: continue
                text.value = row.textBody
                input.reset()
                input
                    .textProvider(textProvider)
                    .classId(classId)
                    .direction(Wire.parse(Direction::class.java, row.direction, Direction.IN))
                    .originator(row.originator)
                    .smsc(row.smsc)
                    .destPort(row.destPort ?: -1)
                    .contentType(row.contentType)
                    .slot(row.slot)
                    .subtype(row.subtype)
                    .roaming(row.roaming ?: false)
                row.cbMessageId?.let { input.cbMessageIdentifier(it) }
                engine.evaluate(input, guards, decision)
                if (!decision.hasRule()) continue
                matches +=
                    PreviewMatch(
                        messageId = row.id,
                        classId = classId,
                        receivedAtWall = row.receivedAtWall,
                        originator = row.originator,
                        action = decision.action(),
                        clampedFrom = decision.clampedFrom(),
                        clampReason = decision.clampReason(),
                    )
            }
            return PreviewResult(
                windowDays = WINDOW_DAYS,
                scanned = rows.size,
                matches = matches,
                elapsedMs = (System.nanoTime() - startedNs) / 1_000_000,
            )
        }

        /** The row's body, resolved by the engine only when a `textContains` condition gets that far. */
        private class TextCell {
            var value: String? = null
        }

        companion object {
            /** window. */
            const val WINDOW_DAYS = 30

            private const val DAY_MS = 24L * 60 * 60 * 1000
        }
    }
