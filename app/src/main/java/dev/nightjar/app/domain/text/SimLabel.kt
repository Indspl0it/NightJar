package dev.nightjar.app.domain.text

import dev.nightjar.app.core.telephony.simNames as joinSimNames

/**
 * "Jio · SIM 1" for a message, from the slot and operator the payload reported. The slot name comes from the string
 * resource `sim_slot_name` (SCREENS1-X02).
 *
 * Unknown parts are left out instead of printed as "?" or "SIM 0": a message with neither gets an empty label, which
 * callers drop from their line.
 */
fun Strings.simDisplay(
    slot: Int,
    carrierName: String?,
): String = simDisplay(slot, carrierName) { number -> slotName(number) }

/**
 * The same label with the English template of `sim_slot_name`, for callers that have no [Strings]. Prefer the
 * [Strings] form, which follows the resources.
 */
fun simDisplay(
    slot: Int,
    carrierName: String?,
): String = simDisplay(slot, carrierName) { number -> Templates.format(TextKey.SIM_SLOT_NAME.template, mapOf("n" to number.toString())) }

private inline fun simDisplay(
    slot: Int,
    carrierName: String?,
    slotLabel: (Int) -> String,
): String {
    val carrier = carrierName?.trim().orEmpty().takeIf { it.isNotEmpty() && !it.equals("unknown", true) }
    val sim = if (slot >= 0) slotLabel(slot + 1) else null
    return listOfNotNull(carrier, sim).joinToString(" · ")
}

private fun Strings.slotName(number: Int): String = Templates.format(get(TextKey.SIM_SLOT_NAME), mapOf("n" to number.toString()))

/**
 * The SIM names for a status line: "SIM 1", "SIM 1 and SIM 2", or "your SIMs" when no ready slot is known or
 * the phone has more than two ready slots. [readySlots] are 0-based, as `SimSlots.readySlots` gives them.
 */
fun Strings.simNames(readySlots: List<Int>): String =
    joinSimNames(
        slots = readySlots,
        slotLabel = { number -> slotName(number) },
        pair = { first, second -> Templates.format(get(TextKey.SIM_PAIR_NAMES), mapOf("first" to first, "second" to second)) },
    ) ?: get(TextKey.MSG_UNKNOWN_SUBS)
