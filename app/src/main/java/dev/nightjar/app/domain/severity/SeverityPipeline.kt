package dev.nightjar.app.domain.severity

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Severity

/**
 * User and context inputs to the severity pipeline.
 *
 * @param overrides `severity.<classId>` settings; a missing entry means "use the default"
 * @param knownOriginators originators the user marked as expected
 * @param trustedPackages packages the user trusted for outbound and injected messages
 */
data class SeverityConfig(
    val overrides: Map<ClassId, Severity> = emptyMap(),
    val knownOriginators: KnownOriginatorMatcher = KnownOriginatorMatcher.EMPTY,
    val trustedPackages: Set<String> = emptySet(),
) {
    fun override(classId: ClassId): Severity? = overrides[classId]

    fun isTrusted(packageName: String?): Boolean = packageName != null && trustedPackages.contains(packageName)

    companion object {
        val DEFAULT = SeverityConfig()
    }
}

/** One message's inputs to the severity pipeline. */
data class SeverityInput(
    val classId: ClassId,
    val direction: Direction = Direction.IN,
    val originator: String? = null,
    val packageName: String? = null,
    /** Outcome: CL-SIM-DD whose SPI showed no integrity and no ciphering (exception). */
    val simOtaInsecure: Boolean = false,
    /** The detector's target severity, applied before the known-originator downgrade. */
    val detectorSeverity: Severity? = null,
    /** The subtype code. Its row gives the default severity when it has one. */
    val subtype: String? = null,
    /** The cell broadcast message identifier. A CB row without a subtype gets its category from it. */
    val cbMessageId: Int? = null,
)

/**
 * The severity pipeline. The order is fixed and tested
 * table-driven:
 *
 * 1. the class default, or the subtype row default when the subtype has a row ([ClassRegistry]);
 * 2. the `severity.<class>` override;
 * 3. the detector's severity, when a detector fired: it replaces the value of steps 1 and 2, up or down (a location
 *    request during an emergency call becomes Info);
 * 4. the known-originator downgrade by one level — except CL-INJECTED, and except CL-SIM-DD whose SPI failed;
 * 5. a trusted package makes outbound and injected messages Info.
 *
 * A user's explicit override is a *starting point*, not a ceiling: a detector may still raise it (a silent SMS burst
 * is Suspicious also when the user set CL-SILENT to Notable). What the message itself proves (the evidence floor) and a
 * suspicious pattern are applied after this pipeline (`SeverityDecider`), so a detector cannot lower them.
 */
object SeverityPipeline {
    fun resolve(
        config: SeverityConfig,
        input: SeverityInput,
    ): Severity {
        var severity = config.override(input.classId) ?: ClassRegistry.defaultSeverity(input.classId, input.subtype, input.cbMessageId)
        input.detectorSeverity?.let { severity = it }
        if (shouldDowngradeForKnownOriginator(config, input)) severity = downgrade(severity)
        if (isTrusted(config, input)) return Severity.INFO
        return severity
    }

    private fun shouldDowngradeForKnownOriginator(
        config: SeverityConfig,
        input: SeverityInput,
    ): Boolean {
        if (input.direction != Direction.IN) return false
        if (input.classId == ClassId.INJECTED) return false
        if (input.classId == ClassId.SIM_DD && input.simOtaInsecure) return false
        return config.knownOriginators.matches(input.originator)
    }

    private fun isTrusted(
        config: SeverityConfig,
        input: SeverityInput,
    ): Boolean {
        if (!config.isTrusted(input.packageName)) return false
        return input.direction == Direction.OUT || input.classId == ClassId.INJECTED
    }

    /** One level down: High → Suspicious → Notable → Info → Info. */
    fun downgrade(severity: Severity): Severity =
        when (severity) {
            Severity.HIGH -> Severity.SUSPICIOUS
            Severity.SUSPICIOUS -> Severity.NOTABLE
            Severity.NOTABLE -> Severity.INFO
            Severity.INFO -> Severity.INFO
        }
}
