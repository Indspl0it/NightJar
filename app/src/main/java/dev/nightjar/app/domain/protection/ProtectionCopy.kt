package dev.nightjar.app.domain.protection

import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ProtectionState

/** Hero card copy for one state (02). */
data class HeroCopy(
    val titleKey: TextKey,
    val subtitleKey: TextKey,
    val titleArgs: Map<String, String> = emptyMap(),
    val subtitleArgs: Map<String, String> = emptyMap(),
)

/**
 * Chooses the Home hero copy and the protection banner copy for a [ProtectionReport].
 *
 * Keeps a user's safe mode inside PROTECTED with the "Blocking paused" badge, so the hero can distinguish
 * "the user asked for this" from "it broke"; both cases must read differently, which is why the badge is a separate
 * pair of strings rather than a sixth state. Each state has a short title and a different subtitle, so the hero never
 * shows the same text two times.
 */
object ProtectionCopy {
    fun hero(
        report: ProtectionReport,
        subNames: String,
    ): HeroCopy =
        when (report.state) {
            ProtectionState.PROTECTED -> {
                if (report.blockingPaused) {
                    HeroCopy(TextKey.MSG_ST_PAUSED, TextKey.MSG_ST_PAUSED_SUB)
                } else if (report.blockingOff) {
                    // The user turned off the Blocking switch. NightJar records but does not block.
                    HeroCopy(TextKey.MSG_ST_BLOCK_OFF, TextKey.MSG_ST_BLOCK_OFF_SUB)
                } else {
                    HeroCopy(
                        titleKey = TextKey.MSG_ST_PROT,
                        subtitleKey = TextKey.MSG_ST_PROT_SUB,
                        subtitleArgs = mapOf("subs" to subNames),
                    )
                }
            }

            // The engine gives neither state: user safe mode stays PROTECTED with blocking paused, and a
            // failed firewall tier stays PROTECTED. The branch keeps the `when` exhaustive over the contract enum.
            ProtectionState.SAFE_MODE, ProtectionState.MONITOR_ONLY -> {
                HeroCopy(TextKey.MSG_ST_PAUSED, TextKey.MSG_ST_PAUSED_SUB)
            }

            ProtectionState.NOT_ACTIVE -> {
                HeroCopy(TextKey.HERO_NA_TITLE, TextKey.HERO_NA_SUB)
            }

            ProtectionState.UNSUPPORTED -> {
                HeroCopy(TextKey.HERO_UNS_TITLE, TextKey.MSG_UNS_01)
            }
        }
}
