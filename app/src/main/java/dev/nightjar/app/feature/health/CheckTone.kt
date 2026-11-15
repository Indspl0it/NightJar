package dev.nightjar.app.feature.health

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.nightjar.app.R
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.theme.NjRoles

/** The colour, icon and badge label of a check state. The label and icon differ per state, so colour is never the only cue. */
internal data class CheckTone(
    val color: Color,
    val icon: ImageVector,
    val label: Int,
)

internal fun checkTone(
    roles: NjRoles,
    state: HealthCheckState,
): CheckTone =
    when (state) {
        HealthCheckState.OK -> CheckTone(roles.ok, NjIcons.Check, R.string.health_x_state_ok)
        HealthCheckState.WARNING -> CheckTone(roles.amb, NjIcons.Diamond, R.string.onb_check_warning)
        HealthCheckState.FAILED -> CheckTone(roles.hi, NjIcons.Warning, R.string.onb_check_failed)
    }
