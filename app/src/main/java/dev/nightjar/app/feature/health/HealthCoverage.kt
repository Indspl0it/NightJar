package dev.nightjar.app.feature.health

import dev.nightjar.app.data.model.Coverage
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Classes that can arrive from the network, in registry order (-04).
 *
 * CL-INJECTED is left out: an app created it locally, so "never seen from the network" would be a false statement
 * rather than useful coverage. CL-STATUS is left out too: a delivery report is not a protection type.
 */
fun networkClasses(): List<ClassId> =
    ClassRegistry
        .all()
        .filter { it.direction == Direction.IN && it.feedVisible && it.classId != ClassId.INJECTED && it.classId != ClassId.STATUS }
        .map { it.classId }

/**
 * Coverage for every class in [classes], including the ones with no row yet.
 *
 * The `coverage` table only gains a row when a class is seen or self-tested; Health must still show "never" for the rest
 * (AC1).
 */
fun orderedCoverage(
    coverage: List<Coverage>,
    classes: List<ClassId> = networkClasses(),
): List<Coverage> {
    val byClass = coverage.associateBy { it.classId }
    return classes.map { classId -> byClass[classId] ?: Coverage(classId, null, null, null) }
}

/** Medium date in the device zone, the form Health uses for coverage and self-test timestamps. */
fun formatMediumDate(wallMs: Long): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(wallMs))
