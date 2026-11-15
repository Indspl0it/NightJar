package dev.nightjar.app.feature.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.R
import dev.nightjar.app.core.security.NumberDisplay
import dev.nightjar.app.core.security.formatNumber
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.model.KnownOriginatorKind
import dev.nightjar.app.data.model.MessageDetail
import dev.nightjar.app.data.model.MessageLink
import dev.nightjar.app.data.model.PartArrival
import dev.nightjar.app.data.repository.DeliveryContext
import dev.nightjar.app.data.repository.KnownOriginatorRepository
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.data.repository.TrustedPackageRepository
import dev.nightjar.app.domain.ClassFamily
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.PrimaryAction
import dev.nightjar.app.domain.cb.CbPage
import dev.nightjar.app.domain.known.TrustedApps
import dev.nightjar.app.domain.model.CbInfo
import dev.nightjar.app.domain.model.ConcatInfo
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.model.EventFlags
import dev.nightjar.app.domain.model.ExtraSubtype
import dev.nightjar.app.domain.text.DateFormatter
import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.app.feature.classify.QuarantineReleaser
import dev.nightjar.app.feature.classify.ReleaseOutcome
import dev.nightjar.app.feature.rules.verdictLabelRes
import dev.nightjar.app.ui.components.NjAction
import dev.nightjar.app.ui.components.TraceRow
import dev.nightjar.app.ui.components.traceRows
import dev.nightjar.contract.CbChannels
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Transport
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.EnforcementMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

/** Parts still missing from a concatenated message; never negative when the payload over-reports (07). */
fun partsWaiting(
    total: Int,
    have: Int,
): Int = (total - have).coerceAtLeast(0)

/** One label/value row of the Detail facts card; [full] rows render their whole value instead of a capped preview. */
data class DetailFact(
    val label: String,
    val value: String,
    val mono: Boolean = false,
    val full: Boolean = false,
)

/** One arrived part of a multipart message (07). */
data class PartRow(
    val index: Int,
    val at: String,
)

/** A linked record shown at the bottom of Detail (08). */
data class LinkedRow(
    val id: Long,
    val title: String,
    val caption: String,
)

/** Everything Detail renders, already localised and formatted. */
data class DetailView(
    val id: Long,
    val eventId: String,
    val title: String,
    val classId: ClassId,
    val severity: Severity,
    val isQuarantined: Boolean,
    val isStarred: Boolean,
    val isReviewed: Boolean,
    val explanation: String,
    val detections: List<String>,
    val facts: List<DetailFact>,
    val fateText: String,
    val raw: ByteArray,
    val hex: String,
    val decoded: List<DecodedNode>,
    val trace: List<TraceRow>,
    val parts: List<PartRow>,
    /** Parts still missing (07); 0 when the message is complete. */
    val partsWaiting: Int,
    /** Localised purge date of an incomplete message, null when Android will keep it. */
    val purgeText: String?,
    val links: List<LinkedRow>,
    val siblings: List<LinkedRow>,
    val primary: PrimaryAction,
    val secondary: PrimaryAction,
    val canRelease: Boolean,
    val originator: String?,
    /** [originator] after the masking preference is applied, for labels. */
    val originatorText: String?,
    val packageName: String?,
    val text: String?,
    /** The family of the type: it picks the header icon. */
    val family: ClassFamily = ClassFamily.SYSTEM,
    /** "SIM 1", or null when the payload did not report a slot. */
    val simLabel: String? = null,
    /** What NightJar did with the message, for the header badge; null for a sent message and a delivery report. */
    val actionBadge: NjAction? = null,
    /** The header line: the masked sender and the time, or the time alone when there is no sender. */
    val senderLine: String = "",
    /** The Evidence card: PID, DCS, user data length, port and service centre. */
    val evidence: List<DetailFact> = emptyList(),
    /** Decoder fields with an error. The Evidence card shows one line for each. It shows no line when this is empty. */
    val formatErrors: List<String> = emptyList(),
    /** The anomaly rows of the Evidence card, in parser order. */
    val anomalies: List<AnomalyRow> = emptyList(),
    /** The other detection rules that matched, in rule order. */
    val extraMatches: List<ExtraMatchRow> = emptyList(),
    /** The "What happened" timeline, oldest step first. */
    val timeline: List<TimelineStep> = emptyList(),
    /** The action buttons, in screen order. */
    val buttons: List<DetailButton> = listOf(DetailButton.DELETE),
    /** Legacy debug flag (always false in current builds): the header shows a "Debug test" badge. */
    val debugTest: Boolean = false,
)

data class DetailUiState(
    val loading: Boolean = true,
    val deleted: Boolean = false,
    /** True when this screen deleted the message, so it confirms instead of popping for "deleted elsewhere". */
    val deletedHere: Boolean = false,
    val view: DetailView? = null,
    val showDeleteDialog: Boolean = false,
    val showReleaseDialog: Boolean = false,
    val showExpectedDialog: Boolean = false,
    val showTrustDialog: Boolean = false,
    val technicalExpanded: Boolean = false,
    val selectedField: DecodedNode? = null,
)

/** What the detail flow combines for each emission: the message, the number masking and the delivery-report links. */
private data class DetailInputs(
    val detail: MessageDetail?,
    val display: NumberDisplay,
    val delivery: DeliveryContext,
)

/** One-off effects of Detail. */
sealed interface DetailEvent {
    data class Snack(
        val text: String,
    ) : DetailEvent

    /** A snackbar with an Undo action that removes the known originator it created. */
    data class SnackUndo(
        val text: String,
        val knownOriginatorId: Long,
    ) : DetailEvent

    data class Copy(
        val text: String,
    ) : DetailEvent

    data class OpenMessage(
        val messageId: Long,
    ) : DetailEvent

    data class OpenRule(
        val messageId: Long,
    ) : DetailEvent

    data object Deleted : DetailEvent
}

sealed interface DetailAction {
    data object ToggleStar : DetailAction

    data class MarkReviewed(
        val reviewed: Boolean,
    ) : DetailAction

    data object AutoReview : DetailAction

    data object CopyText : DetailAction

    data object CopyHex : DetailAction

    data object AskDelete : DetailAction

    data object ConfirmDelete : DetailAction

    data object CancelDelete : DetailAction

    data object MarkExpected : DetailAction

    /** The chosen match mode; prefix marks the sender's number as the beginning of any originator. */
    data class ConfirmMarkExpected(
        val prefix: Boolean,
    ) : DetailAction

    data object CancelMarkExpected : DetailAction

    data class UndoMarkExpected(
        val id: Long,
    ) : DetailAction

    data object Release : DetailAction

    data object ConfirmRelease : DetailAction

    data object CancelRelease : DetailAction

    data object TrustApp : DetailAction

    data object ConfirmTrustApp : DetailAction

    data object CancelTrustApp : DetailAction

    data object OpenRule : DetailAction

    data class OpenMessage(
        val messageId: Long,
    ) : DetailAction

    data object ToggleTechnical : DetailAction

    data class SelectField(
        val node: DecodedNode?,
    ) : DetailAction
}

/**
 *
 * The stored [MessageDetail] is assembled once per emission; copy comes from [Explainer] with the same [EventFacts]
 * the notification used, so the detail screen cannot disagree with the alert the user tapped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DetailViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        @ApplicationContext private val context: Context,
        private val messages: MessageRepository,
        private val knownOriginators: KnownOriginatorRepository,
        private val trustedPackages: TrustedPackageRepository,
        private val settings: SettingsStore,
        private val explainer: Explainer,
        private val dates: DateFormatter,
        private val releasers: Set<@JvmSuppressWildcards QuarantineReleaser>,
    ) : ViewModel() {
        private val target = MutableStateFlow<Long?>(savedState.get<Long>("messageId"))

        private val state = MutableStateFlow(DetailUiState())
        val uiState: StateFlow<DetailUiState> = state.asStateFlow()

        private val events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 8)
        val eventFlow: SharedFlow<DetailEvent> = events.asSharedFlow()

        private val timeFormat: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

        /** The time of a timeline step, with seconds. */
        private val clockFormat: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

        init {
            viewModelScope.launch {
                target
                    .filterNotNull()
                    .distinctUntilChanged()
                    .flatMapLatest { id ->
                        // Masking is a render-time decision: the view is rebuilt when `privacy.maskNumbers` changes, so
                        // turning it on also covers a message that is already open.
                        combine(
                            messages.observe(id),
                            settings.settings.map { it.maskNumbers }.distinctUntilChanged(),
                            messages.observeDeliveryContext(id),
                        ) { detail, mask, delivery -> DetailInputs(detail, NumberDisplay.forSetting(mask), delivery) }
                    }.collect { (detail, display, delivery) ->
                        if (detail == null) {
                            state.update { it.copy(loading = false, deleted = true, view = null) }
                        } else {
                            val known =
                                detail.originator?.let {
                                    runCatching { knownOriginators.isKnown(it) }.getOrDefault(false)
                                } ?: false
                            // Decoding every PDU is CPU work that has no place on the main thread.
                            val view = withContext(Dispatchers.Default) { detail.toView(known, display, delivery) }
                            state.update {
                                it.copy(
                                    loading = false,
                                    deleted = false,
                                    deletedHere = false,
                                    view = view,
                                    selectedField = null,
                                )
                            }
                        }
                    }
            }
        }

        /** Points the ViewModel at a message; the two-pane detail reuses one ViewModel per selection. */
        fun open(messageId: Long) {
            if (target.value != messageId) {
                state.value = DetailUiState()
                target.value = messageId
            }
        }

        fun onAction(action: DetailAction) {
            // Undo outlives the dialog and only needs the id it captured when the entry was created.
            if (action is DetailAction.UndoMarkExpected) {
                undoMarkExpected(action.id)
                return
            }
            val view = state.value.view ?: return
            // Three families, one `when` each: a single dispatch over every action grew past the complexity limit and
            // hid which actions touch the database, which only open a dialog, and which only leave the screen.
            if (onDialogAction(action, view)) return
            if (onMessageAction(action, view)) return
            onScreenAction(action, view)
        }

        /** Dialog open/confirm/cancel actions. Returns false when [action] belongs to another family. */
        private fun onDialogAction(
            action: DetailAction,
            view: DetailView,
        ): Boolean =
            when (action) {
                DetailAction.AskDelete -> {
                    state.update { it.copy(showDeleteDialog = true) }
                    true
                }

                DetailAction.CancelDelete -> {
                    state.update { it.copy(showDeleteDialog = false) }
                    true
                }

                DetailAction.MarkExpected -> {
                    if (view.originator != null) state.update { it.copy(showExpectedDialog = true) }
                    true
                }

                is DetailAction.ConfirmMarkExpected -> {
                    state.update { it.copy(showExpectedDialog = false) }
                    markExpected(view, action.prefix)
                    true
                }

                DetailAction.CancelMarkExpected -> {
                    state.update { it.copy(showExpectedDialog = false) }
                    true
                }

                DetailAction.Release -> {
                    if (view.canRelease) state.update { it.copy(showReleaseDialog = true) }
                    true
                }

                DetailAction.ConfirmRelease -> {
                    state.update { it.copy(showReleaseDialog = false) }
                    release(view)
                    true
                }

                DetailAction.CancelRelease -> {
                    state.update { it.copy(showReleaseDialog = false) }
                    true
                }

                DetailAction.TrustApp -> {
                    if (view.packageName != null) state.update { it.copy(showTrustDialog = true) }
                    true
                }

                DetailAction.ConfirmTrustApp -> {
                    state.update { it.copy(showTrustDialog = false) }
                    trustApp(view)
                    true
                }

                DetailAction.CancelTrustApp -> {
                    state.update { it.copy(showTrustDialog = false) }
                    true
                }

                else -> {
                    false
                }
            }

        /** Actions that write to the database. Returns false when [action] belongs to another family. */
        private fun onMessageAction(
            action: DetailAction,
            view: DetailView,
        ): Boolean =
            when (action) {
                DetailAction.ToggleStar -> {
                    viewModelScope.launch { messages.setStarred(listOf(view.id), !view.isStarred) }
                    true
                }

                is DetailAction.MarkReviewed -> {
                    viewModelScope.launch { messages.markReviewed(listOf(view.id), action.reviewed) }
                    true
                }

                DetailAction.AutoReview -> {
                    viewModelScope.launch {
                        if (settings.current().autoReview && !view.isReviewed) messages.markReviewed(listOf(view.id), true)
                    }
                    true
                }

                DetailAction.ConfirmDelete -> {
                    viewModelScope.launch {
                        // The flag keeps the screen from being popped twice by the "deleted elsewhere" path.
                        state.update { it.copy(showDeleteDialog = false, deletedHere = true) }
                        messages.delete(listOf(view.id))
                        events.emit(DetailEvent.Deleted)
                    }
                    true
                }

                else -> {
                    false
                }
            }

        /** Copy, navigation and disclosure actions: no database write, no dialog. */
        private fun onScreenAction(
            action: DetailAction,
            view: DetailView,
        ) {
            when (action) {
                DetailAction.CopyText -> {
                    view.text?.let { text -> viewModelScope.launch { events.emit(DetailEvent.Copy(text)) } }
                }

                DetailAction.CopyHex -> {
                    viewModelScope.launch { events.emit(DetailEvent.Copy(view.hex)) }
                }

                DetailAction.OpenRule -> {
                    viewModelScope.launch { events.emit(DetailEvent.OpenRule(view.id)) }
                }

                is DetailAction.OpenMessage -> {
                    viewModelScope.launch { events.emit(DetailEvent.OpenMessage(action.messageId)) }
                }

                DetailAction.ToggleTechnical -> {
                    state.update { it.copy(technicalExpanded = !it.technicalExpanded) }
                }

                is DetailAction.SelectField -> {
                    state.update { it.copy(selectedField = action.node) }
                }

                else -> {
                    Unit
                }
            }
        }

        /** Exact or prefix match, with a snackbar Undo that removes the entry again. */
        private fun markExpected(
            view: DetailView,
            prefix: Boolean,
        ) {
            val originator = view.originator ?: return
            viewModelScope.launch {
                val added =
                    runCatching {
                        knownOriginators.add(
                            originator,
                            if (prefix) KnownOriginatorKind.PREFIX else KnownOriginatorKind.EXACT,
                        )
                    }.getOrNull()
                // A failed write must not say "marked as expected": the sender would keep alerting with no explanation.
                if (added == null) {
                    events.emit(DetailEvent.Snack(context.getString(R.string.detail_save_failed)))
                } else {
                    events.emit(DetailEvent.SnackUndo(context.getString(R.string.detail_marked_expected), added.id))
                }
            }
        }

        private fun undoMarkExpected(id: Long) {
            viewModelScope.launch {
                runCatching { knownOriginators.remove(id) }
                events.emit(DetailEvent.Snack(context.getString(R.string.detail_mark_expected_undone)))
            }
        }

        private fun trustApp(view: DetailView) {
            val pkg = view.packageName ?: return
            // Store the app label too. appName gives the package name back when the app has no label.
            val label = explainer.appName(pkg).takeIf { it != pkg }
            viewModelScope.launch {
                val saved = runCatching { trustedPackages.add(pkg, label) }.isSuccess
                events.emit(DetailEvent.Snack(context.getString(if (saved) R.string.detail_trusted else R.string.detail_save_failed)))
            }
        }

        /**
         * → re-injection through the payload (05); honest failure when nothing is bound. A released message
         * is marked released at once, so the screen leaves the held state without waiting for the payload event. A
         * failure shows the text of its code, never the code (APPDATA-X03).
         */
        private fun release(view: DetailView) {
            val releaser = releasers.firstOrNull()
            if (releaser == null) {
                viewModelScope.launch {
                    events.emit(DetailEvent.Snack(context.getString(R.string.detail_release_unavailable)))
                }
                return
            }
            viewModelScope.launch {
                when (val outcome = runCatching { releaser.release(view.id) }.getOrNull()) {
                    is ReleaseOutcome.Released -> {
                        runCatching { messages.setQuarantineState(view.id, quarantined = false) }
                        events.emit(DetailEvent.Snack(context.getString(R.string.detail_released)))
                    }

                    is ReleaseOutcome.Failed -> {
                        events.emit(DetailEvent.Snack(context.getString(releaseFailureRes(outcome.reason))))
                    }

                    null -> {
                        events.emit(DetailEvent.Snack(context.getString(R.string.detail_release_unavailable)))
                    }
                }
            }
        }

        private fun MessageDetail.toView(
            known: Boolean,
            display: NumberDisplay,
            delivery: DeliveryContext,
        ): DetailView {
            val event = toEventFacts()
            val profile = ClassRegistry.of(classId)
            // Release shows only with a bound releaser and a releasable class.
            val releasable = canRelease(isQuarantined, ClassRegistry.isReleasable(classId), releasers.isNotEmpty())
            val raw = rawBytes(pdus)
            val decoded =
                buildList {
                    var base = 0
                    pdus.forEachIndexed { index, pdu ->
                        val nodes = flattenFields(decodePdu(pdu))
                        if (pdus.size > 1) {
                            add(
                                DecodedNode(
                                    context.getString(R.string.detail_part, index + 1),
                                    null,
                                    base,
                                    pdu.bytes.size,
                                    0,
                                    null,
                                ),
                            )
                            addAll(nodes.map { it.copy(offset = it.offset + base, depth = it.depth + 1) })
                        } else {
                            addAll(nodes)
                        }
                        base += pdu.bytes.size
                    }
                }
            val evidence = evidenceOf(decoded)
            val storedTrace = trace
            val traceRowsList =
                buildList {
                    addAll(traceRows(storedTrace.map { Triple(it.stage, it.tNanos, it.detail) }))
                    ackResult?.let {
                        add(
                            TraceRow(
                                stage = context.getString(R.string.detail_ack),
                                relative = "",
                                detail = context.getString(R.string.detail_result, it),
                            ),
                        )
                    }
                    add(
                        TraceRow(
                            stage = context.getString(R.string.detail_fate),
                            relative = "",
                            detail = explainer.fateText(event),
                        ),
                    )
                }
            return DetailView(
                id = id,
                eventId = eventId,
                // Re-worded from the event, so a row stored under the old title reads like a new one.
                title = explainer.title(event),
                classId = classId,
                severity = severity,
                isQuarantined = isQuarantined,
                isStarred = isStarred,
                isReviewed = isReviewed,
                explanation = explainer.explanation(event),
                detections = detections.map { it.summary },
                facts = facts(event, known, display),
                fateText = explainer.fateText(event),
                raw = raw,
                hex = raw.joinToString("") { "%02X".format(it) },
                decoded = decoded,
                trace = traceRowsList,
                parts = arriveRows(parts, receivedAtWall),
                partsWaiting = partsWaiting(partsTotal, partsHave),
                purgeText = purgeAt?.let { dates.date(it) },
                links = listOfNotNull(releasedFrom).map { it.toLinked() } + releasedTo.map { it.toLinked() },
                siblings = siblings.map { it.toLinked() },
                primary =
                    if (isQuarantined) {
                        if (releasable) PrimaryAction.RELEASE else PrimaryAction.NONE
                    } else {
                        profile.primary
                    },
                secondary = profile.secondary,
                canRelease = releasable,
                originator = originator,
                originatorText = formatNumber(originator, display),
                // B4: the app that trust covers, chosen like the classifier chooses it (null hides "Trust app").
                packageName = TrustedApps.target(direction, classId, callingPackage, consumerPackage),
                text = textBody,
                family = profile.family,
                simLabel = if (slot >= 0) context.getString(R.string.detail_sim_slot, slot + 1) else null,
                actionBadge = actionBadgeOf(verdict, isQuarantined, fate, direction, delivery),
                senderLine = senderLine(formatNumber(originator, display), receivedAtWall),
                evidence = evidenceRows(evidence, destPort, formatNumber(smsc, display)) + otaHeaderRows(simOtaHeader),
                formatErrors =
                    evidence.formatErrors.map { context.getString(R.string.detail_x_format_error, it.field, it.message) },
                anomalies = anomalyRows(anomalies, severity) { context.getString(it) },
                extraMatches = extraMatchRows(event),
                timeline = timelineOf(this, event, delivery),
                buttons = detailButtonsFor(isQuarantined, releasable, direction, fate, !EnforcementMatrix.noOwnEnforcementPoint(classId)),
                debugTest = debugInjected,
            )
        }

        /**
         * "Other matching types": each entry gets the title that its class and subtype would give. An entry for a
         * class that this app does not know, or a title that fails, shows the entry as it came.
         */
        private fun extraMatchRows(event: EventFacts): List<ExtraMatchRow> =
            event.extraSubtypes.distinct().map { entry ->
                val match = ExtraSubtype.parse(entry, event.classId)
                val title =
                    match.classId?.let { classId ->
                        runCatching {
                            explainer.title(
                                event.copy(
                                    classId = classId,
                                    subtype = match.subtype,
                                    extraSubtypes = emptyList(),
                                    anomalies = emptyList(),
                                ),
                            )
                        }.getOrNull()
                    }
                ExtraMatchRow(text = title?.takeIf { it.isNotBlank() } ?: entry, code = entry)
            }

        /** The header badge: on hold, blocked or allowed. A sent message and a delivery report have none. */
        private fun actionBadgeOf(
            verdict: Verdict,
            quarantined: Boolean,
            fate: Fate,
            direction: Direction,
            delivery: DeliveryContext,
        ): NjAction? =
            when {
                direction == Direction.OUT || delivery.status != null || delivery.reportOf != null -> null
                outcomeTone(verdict, quarantined, fate) == TimelineTone.HOLD -> NjAction.HOLD
                outcomeTone(verdict, quarantined, fate) == TimelineTone.BLOCK -> NjAction.BLOCK
                else -> NjAction.ALLOW
            }

        private fun senderLine(
            sender: String?,
            wallMs: Long,
        ): String {
            val time = formatDateTime(wallMs)
            return if (sender.isNullOrBlank()) time else context.getString(R.string.detail_from_line, sender, time)
        }

        private fun evidenceRows(
            evidence: Evidence,
            destPort: Int?,
            smsc: String?,
        ): List<DetailFact> {
            // A field without a real value has no row. There is no "None" row.
            return factsWithValue(
                listOfNotNull(
                    evidence.pid?.let { DetailFact(context.getString(R.string.detail_ev_pid), it, mono = true) },
                    evidence.dcs?.let { DetailFact(context.getString(R.string.detail_ev_dcs), it, mono = true) },
                    evidence.udl?.let { DetailFact(context.getString(R.string.detail_ev_udl), it, mono = true) },
                    destPort?.let { DetailFact(context.getString(R.string.detail_ev_dest_port), it.toString(), mono = true) },
                    smsc?.let { DetailFact(context.getString(R.string.detail_ev_smsc), it, mono = true) },
                ),
            )
        }

        /**
         * The OTA command header of a SIM data download, one row per field in payload order. The
         * field names (CPL, CHL, SPI, KIC, KID, TAR, CNTR, in upper case) are the 3GPP TS 31.115 names, so they are not
         * translated.
         */
        private fun otaHeaderRows(header: Map<String, String>): List<DetailFact> =
            header.filterValues { isRealValue(it) }.map { (field, value) ->
                DetailFact(
                    label = context.getString(R.string.detail_ota_field, field.uppercase(Locale.ROOT)),
                    value = value,
                    mono = true,
                )
            }

        /**
         * The "What happened" timeline: what the phone service did, the class, the outcome and the delivery reports. A sent message
         * lists its reports. A delivery report has its status and a link back to its sent message.
         */
        private fun timelineOf(
            detail: MessageDetail,
            event: EventFacts,
            delivery: DeliveryContext,
        ): List<TimelineStep> {
            val at = clockFormat.format(Instant.ofEpochMilli(detail.receivedAtWall))
            return buildList {
                val first = if (detail.direction == Direction.OUT) R.string.detail_x_tl_sent else R.string.detail_x_tl_received
                add(TimelineStep(context.getString(first), at, TimelineTone.INFO))
                add(TimelineStep(context.getString(R.string.detail_x_tl_classified, explainer.title(event)), at, TimelineTone.INFO))
                add(
                    TimelineStep(
                        explainer.fateText(event),
                        at,
                        outcomeTone(detail.verdict, detail.isQuarantined, detail.fate),
                    ),
                )
                delivery.status?.let { add(TimelineStep(reportText(it), at, reportTone(it))) }
                delivery.reportOf?.let {
                    add(TimelineStep(context.getString(R.string.detail_x_tl_report_of), at, TimelineTone.INFO, linkId = it))
                }
                delivery.reports.forEach { report ->
                    val time = clockFormat.format(Instant.ofEpochMilli(report.receivedAtWall))
                    add(TimelineStep(reportText(report.status), time, reportTone(report.status), linkId = report.id))
                }
            }
        }

        private fun reportText(status: String?): String =
            context.getString(
                when (status) {
                    STATUS_DELIVERED -> R.string.detail_x_tl_report_delivered
                    STATUS_PENDING -> R.string.detail_x_tl_report_pending
                    STATUS_FAILED -> R.string.detail_x_tl_report_failed
                    else -> R.string.detail_x_tl_report_unknown
                },
            )

        private fun MessageDetail.facts(
            event: EventFacts,
            known: Boolean,
            display: NumberDisplay,
        ): List<DetailFact> {
            val out = ArrayList<DetailFact>(10)

            fun add(
                labelRes: Int,
                value: String?,
                full: Boolean = false,
            ) {
                if (!value.isNullOrBlank()) out += DetailFact(context.getString(labelRes), value, full = full)
            }
            debugTestFact(
                debugInjected,
                context.getString(R.string.detail_debug_label),
                context.getString(R.string.detail_debug_text),
            )?.let { out += it }
            add(
                R.string.detail_from,
                formatNumber(originator, display)?.let {
                    if (known) "$it (${context.getString(R.string.detail_known)})" else it
                },
            )
            // A sent message: its raw recipient, masked like every other number.
            add(R.string.detail_to, formatNumber(destination, display))
            add(R.string.detail_smsc, formatNumber(smsc, display))
            add(
                R.string.detail_sim,
                listOfNotNull(
                    explainer.sim(event).ifEmpty { null },
                    transportText(transport),
                ).joinToString(" \u00b7 "),
            )
            add(R.string.detail_received, formatDateTime(receivedAtWall))
            add(R.string.detail_sent_by, consumerPackage)
            add(R.string.detail_port, destPort?.toString())
            add(R.string.detail_content_type, contentType)
            add(R.string.detail_message_text, textBody, full = true)
            // The fate detail keys reach the text through Explainer (EventFacts.fateDetail); raw JSON is never shown.
            add(R.string.detail_what_happened, explainer.fateText(event), full = true)
            // A guard changed the rule action; show both actions and the payload's reason.
            clampedFrom?.let { from ->
                add(
                    R.string.detail_clamp_label,
                    context.getString(
                        R.string.detail_clamp,
                        context.getString(verdictLabelRes(from)),
                        context.getString(verdictLabelRes(verdict)),
                    ),
                    full = true,
                )
                clampReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    out += DetailFact(context.getString(R.string.detail_clamp_reason), reason, mono = true)
                }
            }
            return out
        }

        /** The wire enum is never rendered; every transport has a localised name. */
        private fun transportText(transport: Transport): String =
            context.getString(
                when (transport) {
                    Transport.CS -> R.string.detail_transport_cs
                    Transport.IMS -> R.string.detail_transport_ims
                    Transport.CB -> R.string.detail_transport_cb
                    Transport.INJECTED -> R.string.detail_transport_injected
                    Transport.MO_CS -> R.string.detail_transport_mo_cs
                    Transport.MO_IMS -> R.string.detail_transport_mo_ims
                },
            )

        private fun MessageDetail.toEventFacts(): EventFacts =
            EventFacts(
                eventId = eventId,
                direction = direction,
                transport = transport,
                source = source,
                classId = classId,
                fate = fate,
                receivedAtWallMs = receivedAtWall,
                revision = revision,
                subId = subId,
                slot = slot,
                carrierName = carrierName,
                originator = originator,
                destination = destination,
                smsc = smsc,
                destPort = destPort,
                contentType = contentType,
                text = textBody,
                callingPackage = callingPackage,
                persistFlag = persistFlag,
                fateDetail =
                    fateDetailMap(fateDetail) +
                        listOfNotNull(consumerPackage?.let { EventFacts.FATE_DETAIL_PACKAGE to it }),
                verdict = verdict,
                ruleId = ruleId,
                buildFingerprint = buildFingerprint,
                payloadVersion = payloadVersion,
                concat = if (partsTotal > 1) ConcatInfo(ref = concatRef ?: 0, total = partsTotal, have = partsHave) else null,
                cb = cbInfoOf(this),
                // Null means the payload did not report it: the EventFacts defaults (not roaming, unlocked) apply.
                roaming = roaming ?: false,
                userUnlocked = userUnlocked ?: true,
                flags = EventFlags(selfTest = isSelfTest, replay = isReplay, debug = debugInjected),
                subtype = subtype,
                extraSubtypes = extraSubtypes,
                anomalies = anomalies,
            )

        /**
         * The stored `fateDetail` JSON object as text values, the form [EventFacts.fateDetail] has at ingest. Bad JSON
         * gives no keys.
         */
        private fun fateDetailMap(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            return runCatching {
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { key -> obj.opt(key)?.toString().orEmpty() }
            }.getOrDefault(emptyMap())
        }

        /**
         * The CB header of a cell broadcast, so the explanation can name the channel. The stored message id and
         * serial come first; the page numbers come from the first stored page. Null for any other message, or
         * when neither the row nor the page gives a message id.
         */
        private fun cbInfoOf(detail: MessageDetail): CbInfo? {
            if (detail.transport != Transport.CB) return null
            val page = detail.pdus.minByOrNull { it.partNo }?.let { CbPage.of(it.bytes, it.format) } ?: CbPage.UNKNOWN
            val messageId = detail.cbMessageId ?: page.messageId.takeIf { it >= 0 } ?: return null
            return CbInfo(
                serial = detail.cbSerial ?: page.serial,
                messageId = messageId,
                page = page.page,
                pages = page.pages,
                emergency = page.emergency || CbChannels.isEmergency(messageId),
            )
        }

        private fun formatDateTime(wallMs: Long): String =
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(wallMs))

        private fun arriveRows(
            parts: List<PartArrival>,
            receivedAtWall: Long,
        ): List<PartRow> =
            parts.mapIndexed { index, part ->
                PartRow(index + 1, timeFormat.format(Instant.ofEpochMilli(receivedAtOf(part, parts, receivedAtWall))))
            }

        /** Part timestamps are elapsed nanos; the UI shows them relative to the first part. */
        private fun receivedAtOf(
            part: PartArrival,
            parts: List<PartArrival>,
            receivedAtWall: Long,
        ): Long {
            val base = parts.minOfOrNull { it.tNs } ?: part.tNs
            return receivedAtWall + (part.tNs - base) / 1_000_000
        }

        private fun MessageLink.toLinked(): LinkedRow =
            LinkedRow(
                id = id,
                title = title,
                caption = dates.date(receivedAtWall),
            )
    }
