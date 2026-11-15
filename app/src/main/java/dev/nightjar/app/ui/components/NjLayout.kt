package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjStatus
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.NjVerdict

/** Small section label above a [NjGroup] or [NjCard]. [trailing] is an optional action such as "See all". */
@Suppress("ComposableLambdaParameterNaming") // An optional trailing slot, not the main content. Callers pass it by name.
@Composable
fun NjSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = NjSpacing.xs, top = NjSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/**
 * Rounded surface (24 dp, colour `s1`). Content is padded by [padding] (16 dp by default); pass 0.dp
 * when the card holds [NjListRow]s (see [NjGroup]). With [onClick] the whole card is clickable.
 */
@Composable
fun NjCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: androidx.compose.ui.graphics.Color = NjTheme.roles.s1,
    padding: Dp = NjSpacing.m,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inner: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(padding), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = NjShape.card, color = container, content = inner)
    } else {
        Surface(modifier = modifier, shape = NjShape.card, color = container, content = inner)
    }
}

/** A card of rows with hairline dividers between them. Put [NjListRow]/[NjSwitchRow]/[NjChoiceRow] children inside. */
@Composable
fun NjGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    NjCard(modifier = modifier, padding = 0.dp, content = content)
}

/** Hairline between rows inside a [NjGroup]; indented so it starts under the text, not the icon. */
@Composable
fun NjRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = NjSpacing.m),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * Slim inline message: icon, up to two lines of text and an optional text action. Never a block; use it for the global
 * protection banner and for in-screen notes. Text should say something once: pass either [text] alone or a short
 * [title] plus a [text] that does not repeat it.
 */
@Composable
fun NjInfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    kind: NjBannerKind = NjBannerKind.INFO,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tone =
        when (kind) {
            NjBannerKind.INFO -> NjTheme.colors.of(NjVerdict.INFO)
            NjBannerKind.WARN -> NjTheme.colors.of(NjStatus.DEGRADED)
            NjBannerKind.ERROR -> NjTheme.colors.of(NjVerdict.SUSPICIOUS)
        }
    val icon =
        when (kind) {
            NjBannerKind.INFO -> NjIcons.Info
            NjBannerKind.WARN -> NjIcons.Diamond
            NjBannerKind.ERROR -> NjIcons.Warning
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(NjShape.chip)
                .background(tone.container)
                .heightIn(min = 40.dp)
                .padding(start = NjSpacing.m - 2.dp, end = NjSpacing.xs, top = NjSpacing.xs, bottom = NjSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tone.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(NjSpacing.s))
            Column(Modifier.padding(vertical = NjSpacing.xs)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = tone.content, maxLines = 1)
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = tone.content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = tone.accent, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/**
 * The state card at the top of Home: round icon, [title], one line of [body] and, when something needs fixing, a single
 * action button ([actionLabel] + [onAction]). Colours come from [status]. [body] is dropped when it repeats [title].
 */
@Suppress("ComposableLambdaParameterNaming") // An optional extra slot, not the main content. Callers pass it by name.
@Composable
fun NjStatusHero(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    status: NjStatus = NjStatus.PROTECTED,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tone = NjTheme.colors.of(status)
    val icon =
        if (status == NjStatus.PROTECTED) {
            NjIcons.Shield
        } else if (status == NjStatus.DEGRADED) {
            NjIcons.Diamond
        } else {
            NjIcons.Warning
        }
    val secondary = body?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
    NjCard(modifier = modifier, onClick = onClick, container = tone.container, padding = NjSpacing.l - 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(tone.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tone.accent, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(NjSpacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NjSpacing.xs)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = tone.content,
                    modifier = Modifier.semantics { heading() },
                )
                if (secondary != null) {
                    Text(
                        secondary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tone.content,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(NjSpacing.m))
            androidx.compose.material3.Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors =
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = tone.accent,
                        contentColor = tone.container,
                    ),
            ) { Text(actionLabel) }
        }
        extra?.invoke(this)
    }
}

/**
 * Empty or "nothing here" state: the bird artwork (or a custom [icon]), [title], [body] and one button. Give the button
 * with [actionLabel] and [onAction], or pass a custom [action] composable. Always give the user a next step in the
 * button or in [body].
 */
@Suppress("ComposableLambdaParameterNaming") // An optional action slot, not the main content. Callers pass it by name.
@Composable
fun NjEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(NjSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        if (icon == null) {
            NjBird(modifier = Modifier.width(NjBirdWidth))
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = NjTheme.roles.mu,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = NjTheme.roles.tx,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = NjTheme.roles.mu,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            action()
        } else if (actionLabel != null && onAction != null) {
            NjTonalButton(text = actionLabel, onClick = onAction)
        }
    }
}
