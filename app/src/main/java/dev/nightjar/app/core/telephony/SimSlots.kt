package dev.nightjar.app.core.telephony

import android.content.Context
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SIM slots that hold a ready SIM card ("SIM names").
 *
 * The app has no READ_PHONE_STATE permission, so it cannot read the subscription display names. It shows "SIM 1" and
 * "SIM 2" instead (copy standard section 4). [TelephonyManager.getActiveModemCount] and
 * [TelephonyManager.getSimState] need no permission. Both are binder calls into the phone process, so call
 * [readySlots] off the main thread.
 */
@Singleton
class SimSlots
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** The 0-based slots with a ready SIM card, in slot order. Empty when the phone does not answer. */
        fun readySlots(): List<Int> =
            runCatching {
                val telephony = context.getSystemService(TelephonyManager::class.java) ?: return@runCatching emptyList()
                (0 until telephony.activeModemCount).filter { slot ->
                    telephony.getSimState(slot) == TelephonyManager.SIM_STATE_READY
                }
            }.getOrDefault(emptyList())
    }

/**
 * The SIM names for a status line: "SIM 1", "SIM 1 and SIM 2", or null when no slot is known or the phone has more than
 * two ready slots (the caller then writes "your SIMs"). [slotLabel] gets the 1-based slot number. [pair] joins two
 * labels. The function is inline, so a composable can pass lambdas that call `stringResource`.
 */
inline fun simNames(
    slots: List<Int>,
    slotLabel: (Int) -> String,
    pair: (String, String) -> String,
): String? =
    when (slots.size) {
        1 -> slotLabel(slots[0] + 1)
        2 -> pair(slotLabel(slots[0] + 1), slotLabel(slots[1] + 1))
        else -> null
    }
