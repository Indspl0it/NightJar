package dev.nightjar.app.feature.firewall

import androidx.annotation.StringRes
import dev.nightjar.app.R
import dev.nightjar.app.feature.rules.classLabelRes
import dev.nightjar.contract.ClassId

/**
 * The type name of a Protection row: a short name, so a row does
 * not wrap next to its icon and bell. Only this screen uses them. The rule editor and the other screens keep
 * [classLabelRes], which adds the type code ("Silent SMS (Type 0)").
 *
 * A type keeps the long label when the short name would be the same as the name of another type. For example
 * "Location request (SUPL)" stays, because "Location request" is the WAP location request.
 */
@StringRes
fun protectionLabelRes(classId: ClassId): Int = protectionShortLabels[classId] ?: classLabelRes(classId)

private val protectionShortLabels: Map<ClassId, Int> =
    mapOf(
        ClassId.SILENT to R.string.prot_silent,
        ClassId.FLASH to R.string.prot_flash,
    )
