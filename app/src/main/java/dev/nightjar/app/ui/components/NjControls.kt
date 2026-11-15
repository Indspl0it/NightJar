package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.NjVerdict
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.contract.Severity

// ------------------------------------------------------------------ buttons

/** The single most important action on a screen. Filled. Use [destructive] for irreversible actions. */
@Composable
fun NjPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors =
            if (destructive) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
    ) {
        ButtonContent(text, icon)
    }
}

/** A secondary action. Tonal fill. */
@Composable
fun NjTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled) {
        ButtonContent(text, icon)
    }
}

/**
 * A destructive action on a screen (Block similar, Delete): `s2` fill with the text in `hi`. The
 * confirm button of a dialog that cannot be undone uses [NjDialog] with `destructive = true`.
 */
@Composable
fun NjDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val roles = NjTheme.roles
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = roles.s2, contentColor = roles.hi),
    ) {
        ButtonContent(text, icon)
    }
}

/** A low-emphasis action (Cancel, Learn more). No fill. */
@Composable
fun NjTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors =
            if (destructive) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.textButtonColors()
            },
    ) {
        Text(text)
    }
}

@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector?,
) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(NjSpacing.s))
    }
    Text(text)
}

// ------------------------------------------------------------------ chips

/** Non-interactive label chip (12 dp corners) in custom colours. Prefer [NjVerdictChip] for message outcomes. */
@Composable
fun NjChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier =
            modifier
                .clip(NjShape.chip)
                .background(container)
                .padding(horizontal = NjSpacing.s + 2.dp, vertical = NjSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(NjSpacing.xs))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/** Outcome chip: coloured by [verdict] (hidden, suspicious, quarantined, info) with an icon, never colour alone. */
@Composable
fun NjVerdictChip(
    text: String,
    verdict: NjVerdict,
    modifier: Modifier = Modifier,
) {
    val tone = NjTheme.colors.of(verdict)
    val icon =
        when (verdict) {
            NjVerdict.HIDDEN -> NjIcons.Diamond
            NjVerdict.SUSPICIOUS -> NjIcons.Warning
            NjVerdict.QUARANTINED -> NjIcons.Lock
            NjVerdict.INFO -> NjIcons.Info
        }
    NjChip(text = text, modifier = modifier, icon = icon, container = tone.container, content = tone.content)
}

/** [NjVerdictChip] for a contract [Severity], using the severity's own label. */
@Composable
fun NjVerdictChip(
    severity: Severity,
    modifier: Modifier = Modifier,
) {
    NjVerdictChip(
        text =
            dev.nightjar.app.ui.theme.SeverityTokens
                .label(severity),
        verdict = NjVerdict.fromSeverity(severity),
        modifier = modifier,
    )
}

/** A horizontally scrolling row of filter chips. Multi-select: [selected] is the set of active values. */
@Composable
fun <T> NjFilterChips(
    options: List<NjOption<T>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option.value in selected,
                onClick = { onToggle(option.value) },
                label = { Text(option.label, maxLines = 1) },
                shape = NjShape.chip,
            )
        }
    }
}

// ------------------------------------------------------------------ text fields

/** Pill-shaped search box with a leading magnifier and a clear button once there is text. */
@Composable
fun NjSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(NjIcons.Search, contentDescription = null) },
        trailingIcon =
            if (value.isNotEmpty()) {
                {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(NjIcons.Close, contentDescription = stringResource(R.string.nj_cmp_search_clear))
                    }
                }
            } else {
                null
            },
        singleLine = true,
        shape = NjShape.pill,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
            ),
    )
}

/** Standard outlined text field (16 dp corners) with an optional [supporting] line under it. */
@Composable
fun NjTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText =
            if (supporting != null) {
                { Text(supporting) }
            } else {
                null
            },
        isError = isError,
        singleLine = singleLine,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        shape = NjShape.field,
    )
}

// ------------------------------------------------------------------ overlays

/**
 * Modal bottom sheet with an optional [title]. Content scrolls if it is taller than the sheet. Dismiss with a swipe, the
 * scrim or back; always call [onDismiss] to hide it. With [dismissible] false the sheet stays open until the caller
 * removes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NjBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    // With [dismissible] false, a swipe, a scrim tap and Back cannot hide the sheet (the caller would refuse the
    // dismiss, and a hidden sheet would leave an invisible modal on the screen).
    val canHide by rememberUpdatedState(dismissible)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { value -> canHide || value != SheetValue.Hidden },
            ),
        sheetGesturesEnabled = dismissible,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = NjSpacing.m),
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier =
                        Modifier
                            .padding(horizontal = NjSpacing.m)
                            .padding(bottom = NjSpacing.s)
                            .semantics { heading() },
                )
            }
            content()
        }
    }
}

/** A bottom sheet that lists [options] as radio rows; tapping one calls [onSelect] (the caller closes the sheet). */
@Composable
fun <T> NjChoiceSheet(
    title: String,
    options: List<NjOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    NjBottomSheet(onDismiss = onDismiss, title = title) {
        options.forEach { option ->
            NjChoiceRow(
                title = option.label,
                supporting = option.supporting,
                selected = option.value == selected,
                onSelect = { onSelect(option.value) },
            )
        }
    }
}

/**
 * Confirmation / form dialog. [text] is a plain message; [content] adds a form below it. The confirm button is red when
 * [destructive]. Pass [dismissText] = null for a single-button acknowledgement.
 */
@Composable
fun NjDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissText: String? = stringResource(R.string.action_cancel),
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = NjShape.card,
        title = { Text(title) },
        text =
            if (text != null || content != null) {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
                        if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
                        content?.invoke(this)
                    }
                }
            } else {
                null
            },
        confirmButton = {
            NjTextButton(text = confirmText, onClick = onConfirm, enabled = confirmEnabled, destructive = destructive)
        },
        dismissButton =
            if (dismissText != null) {
                { NjTextButton(text = dismissText, onClick = onDismiss) }
            } else {
                null
            },
    )
}

/** Tonal "disabled" caption under a control, 12 sp. */
@Composable
fun NjCaption(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(name = "Danger and top bar buttons dark", widthDp = 360)
@Composable
private fun NjDangerButtonDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { NjDangerButtonSample() }
}

@Preview(name = "Danger and top bar buttons light", widthDp = 360)
@Composable
private fun NjDangerButtonLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { NjDangerButtonSample() }
}

@Composable
private fun NjDangerButtonSample() {
    Row(
        modifier = Modifier.background(NjTheme.roles.bg).padding(NjSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NjDangerButton(text = stringResource(R.string.home_action_block_similar), onClick = {})
        NjTopBarIconButton(icon = NjIcons.Search, contentDescription = stringResource(R.string.home_search_messages), onClick = {})
        NjTopBarIconButton(icon = NjIcons.Tune, contentDescription = stringResource(R.string.home_filter_messages), onClick = {})
    }
}
