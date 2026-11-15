package dev.nightjar.app.ui.components

import androidx.compose.ui.graphics.Color
import dev.nightjar.app.R
import dev.nightjar.app.ui.theme.NjRoles

/** What Nightjar does with a class of message (redesign 7.1): let it through, hold it, or block it. */
enum class NjAction { ALLOW, HOLD, BLOCK }

/** Allow = `ok`, Hold = `amb`, Block = `hi` (redesign 7.1). */
internal fun njActionColor(
    roles: NjRoles,
    action: NjAction,
): Color =
    when (action) {
        NjAction.ALLOW -> roles.ok
        NjAction.HOLD -> roles.amb
        NjAction.BLOCK -> roles.hi
    }

/** The label resource of an action. */
internal fun njActionLabelRes(action: NjAction): Int =
    when (action) {
        NjAction.ALLOW -> R.string.nj_cmp_action_allow
        NjAction.HOLD -> R.string.nj_cmp_action_hold
        NjAction.BLOCK -> R.string.nj_cmp_action_block
    }
