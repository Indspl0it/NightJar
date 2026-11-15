package dev.nightjar.app.feature.logs

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.model.AuditEntry
import dev.nightjar.app.feature.rules.ToolGroupRow
import dev.nightjar.app.feature.rules.ToolRowPosition
import dev.nightjar.app.feature.rules.ToolScreen
import dev.nightjar.app.feature.rules.toolCard
import dev.nightjar.app.feature.rules.toolLoading
import dev.nightjar.app.feature.settings.UiFormat
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjFilterChips
import dev.nightjar.app.ui.components.NjListRow
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.nav.NavActions
import dev.nightjar.app.ui.theme.PreviewTheme

/** Audit history. */
@Composable
fun AuditHistoryScreen(actions: NavActions) {
    val audit = auditBinding()
    AuditHistoryContent(state = audit.state, onSelect = audit.onSelect, onBack = { actions.back() })
}

@Composable
private fun AuditHistoryContent(
    state: AuditUiState,
    onSelect: (AuditAction?) -> Unit,
    onBack: () -> Unit,
) {
    ToolScreen(title = stringResource(R.string.audit_title), onBack = onBack) {
        auditItems(state = state, onSelect = onSelect, firstIndex = 1)
    }
}

/**
 * The audit list as list items: the action filter chips, then one group row for each entry. [firstIndex] is the stagger
 * index of the chips.
 */
internal fun LazyListScope.auditItems(
    state: AuditUiState,
    onSelect: (AuditAction?) -> Unit,
    firstIndex: Int,
) {
    item(key = "audit-filter") {
        val options =
            listOf(NjOption<AuditAction?>(null, stringResource(R.string.audit_filter_all))) +
                state.actions.map { NjOption<AuditAction?>(it, stringResource(auditActionLabel(it))) }
        NjFilterChips(
            options = options,
            selected = setOf(state.selected),
            onToggle = onSelect,
            modifier = Modifier.toolCard().njEnter(firstIndex),
        )
    }
    val entries = state.filtered
    when {
        state.loading -> {
            toolLoading(4)
        }

        entries.isEmpty() -> {
            item(key = "audit-empty") {
                NjEmptyState(
                    title = stringResource(R.string.audit_title),
                    body = stringResource(R.string.audit_empty),
                    modifier = Modifier.njEnter(firstIndex + 1),
                )
            }
        }

        else -> {
            itemsIndexed(entries, key = { _, entry -> "audit-" + entry.id }) { index, entry ->
                AuditRow(
                    entry = entry,
                    position = ToolRowPosition.of(index, entries.size),
                    modifier = Modifier.njEnter(firstIndex + 1 + index),
                )
            }
        }
    }
}

/** One audit entry: the action, the time on the right, the actor and the target below. */
@Composable
private fun AuditRow(
    entry: AuditEntry,
    position: ToolRowPosition,
    modifier: Modifier = Modifier,
) {
    val action =
        AuditAction.from(entry.action)?.let { stringResource(auditActionLabel(it)) }
            ?: entry.action
    // The audit tab is part of Logs, so log masking applies: a target such as a known sender can hold a full number.
    val target =
        entry.target
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.audit_target, LogRedactor.redact(it)) }
    ToolGroupRow(position = position, modifier = modifier) {
        NjListRow(
            title = action,
            supporting = listOfNotNull(actorLabel(entry.actor), target).joinToString("\n"),
            trailingValue = UiFormat.dateTime(entry.at),
        )
    }
}

@Composable
private fun actorLabel(actor: String): String =
    stringResource(
        when (AuditActor.from(actor)) {
            AuditActor.USER -> R.string.audit_actor_user
            AuditActor.PRESET -> R.string.audit_actor_preset
            AuditActor.SYSTEM -> R.string.audit_actor_system
        },
    )

// One branch per AuditAction, so a new action cannot ship without a label. A lookup table would lose that check.
@Suppress("CyclomaticComplexMethod")
@StringRes
internal fun auditActionLabel(action: AuditAction): Int =
    when (action) {
        AuditAction.RULE_CREATE -> R.string.audit_action_rule_create
        AuditAction.RULE_UPDATE -> R.string.audit_action_rule_update
        AuditAction.RULE_DELETE -> R.string.audit_action_rule_delete
        AuditAction.RULE_REORDER -> R.string.audit_action_rule_reorder
        AuditAction.PRESET_CHANGE -> R.string.audit_action_preset_change
        AuditAction.CLASS_ACTION_CHANGE -> R.string.audit_action_class_action_change
        AuditAction.SAFE_MODE_ON -> R.string.audit_action_safe_mode_on
        AuditAction.SAFE_MODE_OFF -> R.string.audit_action_safe_mode_off
        AuditAction.RELEASE -> R.string.audit_action_release
        AuditAction.BLOCK_HELD -> R.string.audit_action_block_held
        AuditAction.DELETE_MESSAGES -> R.string.audit_action_delete_messages
        AuditAction.DELETE_ALL -> R.string.audit_action_delete_all
        AuditAction.EXPORT -> R.string.audit_action_export
        AuditAction.MODULE_INSTALL -> R.string.audit_action_module_install
        AuditAction.MODULE_UPDATE -> R.string.audit_action_module_update
        AuditAction.MODULE_REMOVE -> R.string.audit_action_module_remove
        AuditAction.FIREWALL_ENABLE -> R.string.audit_action_firewall_enable
        AuditAction.FIREWALL_DISABLE -> R.string.audit_action_firewall_disable
        AuditAction.OUTGOING_BLOCK -> R.string.audit_action_outgoing_block
        AuditAction.OUTGOING_UNBLOCK -> R.string.audit_action_outgoing_unblock
        AuditAction.KNOWN_SENDER_ADD -> R.string.audit_action_known_sender_add
        AuditAction.KNOWN_SENDER_REMOVE -> R.string.audit_action_known_sender_remove
        AuditAction.TRUST_APP_ADD -> R.string.audit_action_trust_app_add
        AuditAction.TRUST_APP_REMOVE -> R.string.audit_action_trust_app_remove
        AuditAction.SETTINGS_CHANGE -> R.string.audit_action_settings_change
        AuditAction.SELF_TEST_RUN -> R.string.audit_action_self_test_run
        AuditAction.PHONE_RESTART -> R.string.audit_action_phone_restart
    }

// ------------------------------------------------------------------ previews

private val PreviewAuditState =
    AuditUiState(
        loading = false,
        entries =
            listOf(
                AuditEntry(
                    id = 1,
                    at = 1_760_000_000_000,
                    actor = AuditActor.USER.name,
                    action = AuditAction.RULE_CREATE.name,
                    target = "rule:5",
                    beforeJson = null,
                    afterJson = null,
                ),
                AuditEntry(
                    id = 2,
                    at = 1_760_000_100_000,
                    actor = AuditActor.SYSTEM.name,
                    action = AuditAction.SELF_TEST_RUN.name,
                    target = null,
                    beforeJson = null,
                    afterJson = null,
                ),
            ),
        actions = listOf(AuditAction.RULE_CREATE, AuditAction.SELF_TEST_RUN),
    )

@Preview(name = "Audit dark", widthDp = 400, heightDp = 640)
@Composable
private fun AuditDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        AuditHistoryContent(state = PreviewAuditState, onSelect = {}, onBack = {})
    }
}

@Preview(name = "Audit light", widthDp = 400, heightDp = 640)
@Composable
private fun AuditLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        AuditHistoryContent(state = PreviewAuditState, onSelect = {}, onBack = {})
    }
}

@Preview(name = "Audit empty dark", widthDp = 400, heightDp = 640)
@Composable
private fun AuditEmptyDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        AuditHistoryContent(state = AuditUiState(loading = false), onSelect = {}, onBack = {})
    }
}
