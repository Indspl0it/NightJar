package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.model.EmergencyCallState
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.contract.ClassId

/** A silent ping already stored (burst window). */
data class SilentPingRecord(
    val eventId: String,
    val originator: String?,
    val atWallMs: Long,
)

/**
 * An open detection that a new finding may grow.
 *
 * Members are not repeated here: the store already holds them, and the burst's sender count comes from the silent
 * pings inside the window, which the detector has to read anyway.
 */
data class OpenDetection(
    val detectorId: DetectorId,
    val mergeKey: String,
    val firstAtWallMs: Long,
    val lastAtWallMs: Long,
    val count: Int,
)

/**
 * Read model the detectors need. The data layer implements it over Room; tests use an in-memory fake. Every method
 * is a suspending database read, called on the ingest worker and never from the UI thread.
 */
interface DetectionHistory {
    /** Silent pings stored for this device with `atWallMs >= sinceWallMs`, oldest first. */
    suspend fun silentPingsSince(
        sinceWallMs: Long,
        limit: Int,
    ): List<SilentPingRecord>

    /** True when a message from this normalised originator was stored before [beforeWallMs]. */
    suspend fun originatorSeenBefore(
        normalizedOriginator: String,
        beforeWallMs: Long,
    ): Boolean

    /** True when this package has sent a message before [beforeWallMs] ("never sent before"). */
    suspend fun packageSentBefore(
        packageName: String,
        beforeWallMs: Long,
    ): Boolean

    /**
     * True when the phone sent a message to [destination] before [beforeWallMs] (STK sends). A null or
     * unreadable destination counts as never sent to.
     */
    suspend fun destinationSentBefore(
        destination: String?,
        beforeWallMs: Long,
    ): Boolean

    /** The open detection for a detector, if any (the store keeps at most one open row per detector + mergeKey). */
    suspend fun openDetection(
        detectorId: DetectorId,
        mergeKey: String,
    ): OpenDetection?

    /** The most recently opened detection for a detector, used to decide whether a burst continues. */
    suspend fun latestOpenDetection(detectorId: DetectorId): OpenDetection?
}

/** Wall clock, injectable so detector tests are deterministic. */
fun interface WallClock {
    fun nowWallMs(): Long
}

/** Facts about a CL-SIM-DD that only the decoder can supply. */
data class SimOtaFacts(
    /** Two SPI octets, big-endian: `spi1 shl 8 or spi2`. */
    val spi: Int,
    /** Three TAR octets, big-endian. */
    val tar: Int,
) {
    /** TS 102 225 SPI1 b2b1. */
    enum class Integrity { NONE, REDUNDANCY_CHECK, CRYPTOGRAPHIC_CHECKSUM, DIGITAL_SIGNATURE }

    /** TS 102 225 SPI2 b8b7. */
    enum class PorMode { NONE, REQUIRED, ON_ERROR, RESERVED }

    val spi1: Int get() = (spi shr 8) and 0xFF

    val spi2: Int get() = spi and 0xFF

    val integrity: Integrity get() = Integrity.entries[spi1 and 0b11]

    /** SPI1 b3. */
    val ciphering: Boolean get() = (spi1 and 0b100) != 0

    /** SPI1 b5b4: 0 no counter, 1 counter without replay checking, 2 monotonic, 3 strictly increasing. */
    val counterMode: Int get() = (spi1 shr 3) and 0b11

    val porMode: PorMode get() = PorMode.entries[(spi2 shr 6) and 0b11]

    /** True when the packet carries no cryptographic protection at all (AC2). */
    val insecure: Boolean
        get() = (integrity == Integrity.NONE || integrity == Integrity.REDUNDANCY_CHECK) && !ciphering

    /** TAR as 6 upper-case hex digits, for the explanation and for the denylist lookup. */
    val tarHex: String get() = "%06X".format(tar and 0xFFFFFF)

    companion object {
        fun parse(
            spi1: Int,
            spi2: Int,
            tar: Int,
        ): SimOtaFacts = SimOtaFacts(((spi1 and 0xFF) shl 8) or (spi2 and 0xFF), tar and 0xFFFFFF)
    }
}

/** Values of an OMA CP settings push, decoded by the OMA CP decoder. Absent when the decoder could not decode. */
data class OmaCpFacts(
    val apn: String? = null,
    val proxy: String? = null,
    val server: String? = null,
    val homePage: String? = null,
) {
    val isEmpty: Boolean get() = apn == null && proxy == null && server == null && homePage == null

    companion object {
        val UNKNOWN = OmaCpFacts()
    }
}

/** Everything about one event that detectors and the severity pipeline may need beyond [EventFacts] itself. */
data class DetectContext(
    val emergency: EmergencyCallState = EmergencyCallState.NONE,
    val simOta: SimOtaFacts? = null,
    val omaCp: OmaCpFacts = OmaCpFacts.UNKNOWN,
) {
    companion object {
        val NONE = DetectContext()
    }
}

/**
 * True while an emergency call is active, and for [graceMs] after it ends. A location request inside
 * this window is expected: the network locates the phone for the emergency service.
 */
internal fun DetectContext.withinEmergencyGrace(
    atWallMs: Long,
    graceMs: Long,
): Boolean = emergency.active || emergency.endedAtWallMs?.let { atWallMs - it in 0 until graceMs } == true

/** Runs every detector that applies to an event. */
interface Detectors {
    suspend fun evaluate(
        event: EventFacts,
        context: DetectContext,
    ): List<DetectionDraft>
}

/** Convenience for detectors that only apply to one class. */
internal fun EventFacts.isClass(vararg classes: ClassId): Boolean = classId in classes
