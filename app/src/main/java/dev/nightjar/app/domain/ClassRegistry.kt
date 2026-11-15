package dev.nightjar.app.domain

import dev.nightjar.app.domain.cb.CbCategory
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.PortNames
import dev.nightjar.contract.Severity
import dev.nightjar.rules.EnforcementMatrix

/**
 * Class families. Each family has one avatar shape and one notification group.
 *
 * The shape and icon names are plain identifiers: the UI layer owns the actual shapes and Material Symbols vectors,
 * so this module stays free of Compose and Android imports.
 */
enum class ClassFamily(
    val wire: String,
    val shape: String,
    val icon: String,
    val labelKey: TextKey,
) {
    SILENT("SILENT", "cookie4", "notifications_off", TextKey.FAMILY_SILENT),
    SIM("SIM", "softSquare", "sim_card", TextKey.FAMILY_SIM),
    WAP("WAP", "scallop", "language", TextKey.FAMILY_WAP),
    VOICEMAIL("VOICEMAIL", "pill", "voicemail", TextKey.FAMILY_VOICEMAIL),
    APP("APP", "hexagon", "apps", TextKey.FAMILY_APP),
    BROADCAST("BROADCAST", "burst", "campaign", TextKey.FAMILY_BROADCAST),
    OUTBOUND("OUTBOUND", "arrowTile", "send", TextKey.FAMILY_OUTBOUND),
    SYSTEM("SYSTEM", "roundedSquare", "info", TextKey.FAMILY_SYSTEM),
}

/** Primary action on Detail. `NONE` means the class is information-only. */
enum class PrimaryAction {
    NONE,
    MARK_EXPECTED,
    BLOCK_LIKE_THIS,
    BLOCK_SIM_UPDATES,
    RELEASE,
    TRUST_APP,
    COPY_TEXT,
}

/**
 * Everything the app knows about one message class.
 *
 * The allowed firewall actions and the releasable flag are **not** duplicated here: they come from [EnforcementMatrix]
 * (as code), so the class action sheet, the rule validator and the payload cannot drift from the registry.
 */
data class ClassProfile(
    val classId: ClassId,
    val family: ClassFamily,
    val defaultSeverity: Severity,
    val direction: Direction,
    /** False for counter-only classes (CL-NORMAL, CL-MO-VISIBLE). */
    val feedVisible: Boolean,
    val primary: PrimaryAction,
    val secondary: PrimaryAction,
    val titleKey: TextKey,
    val explanationKey: TextKey,
) {
    /**
     * "Releasable": quarantine can be undone by re-injecting the stored PDUs. It comes from the one table in
     * :rules ([EnforcementMatrix.releasable]), which the payload uses too, so the app cannot offer a Release that the
     * payload refuses.
     */
    val releasable: Boolean get() = EnforcementMatrix.releasable(classId)
}

/**
 * One subtype row below its class: the subtype [codes] that share a title, an explanation and a default
 * severity. The class stays the unit for the user's action (Allow, Hold, Block). A subtype only adds detail.
 */
data class SubtypeProfile(
    val classId: ClassId,
    val codes: Set<String>,
    val severity: Severity,
    val titleKey: TextKey,
    val explanationKey: TextKey,
)

/**
 * The single class table. Every [ClassId] has exactly one entry; [ClassRegistryTest] fails when one is
 * missing, so a contract change cannot silently produce a class with no severity, title or explanation.
 */
object ClassRegistry {
    private val entries: Map<ClassId, ClassProfile> = CLASS_PROFILES

    private val subtypeRows: Map<ClassId, List<SubtypeProfile>> = SUBTYPE_PROFILES

    fun of(classId: ClassId): ClassProfile = entries.getValue(classId)

    fun all(): List<ClassProfile> = ClassId.values().map { of(it) }

    fun familyOf(classId: ClassId): ClassFamily = of(classId).family

    fun defaultSeverity(classId: ClassId): Severity = of(classId).defaultSeverity

    fun isFeedVisible(classId: ClassId): Boolean = of(classId).feedVisible

    fun isReleasable(classId: ClassId): Boolean = EnforcementMatrix.releasable(classId)

    /** Classes that only feed counters: the classes that are not in the feed (CL-NORMAL and CL-MO-VISIBLE). */
    fun isCounterOnly(classId: ClassId): Boolean = !isFeedVisible(classId)

    /** The subtype row of [code] in [classId], or null for no subtype or a code without its own row. */
    fun subtype(
        classId: ClassId,
        code: String?,
    ): SubtypeProfile? = code?.let { c -> subtypeRows[classId]?.firstOrNull { c in it.codes } }

    /** Every subtype row of [classId], in table order (empty for a class without subtypes). */
    fun subtypes(classId: ClassId): List<SubtypeProfile> = subtypeRows[classId].orEmpty()

    /**
     * The default severity of the subtype row: the [CbCategory] row for a cell broadcast (by subtype or
     * [cbMessageId]), the subtype row when the code has one, else the class default.
     */
    fun defaultSeverity(
        classId: ClassId,
        subtype: String?,
        cbMessageId: Int? = null,
    ): Severity =
        CbCategory.defaultSeverity(classId, subtype, cbMessageId)
            ?: subtype(classId, subtype)?.severity
            ?: defaultSeverity(classId)

    /**
     * The subtype of a message: the payload code, or for a port class without a code the port table entry
     * ([PortNames]). Rows from before the subtype field have no code, so their title still names the port.
     */
    fun effectiveSubtype(
        classId: ClassId,
        subtype: String?,
        destPort: Int?,
    ): String? {
        if (subtype != null || destPort == null) return subtype
        return if (PortNames.classOf(destPort) == classId) PortNames.subtype(destPort) else null
    }
}
