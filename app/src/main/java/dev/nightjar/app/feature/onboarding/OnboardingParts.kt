package dev.nightjar.app.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.nightjar.app.R
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme

/*
 * The parts that the three onboarding screens share: the
 * "STEP n OF 3" label at the top, the dots above the bottom button, the soft glow behind the top, and the scroll area
 * between them.
 */

/** Side gutter of the onboarding screens (20 dp). */
internal val SetupGutter = NjSpacing.m + NjSpacing.xs

/** Semibold title in the system font (weight 600, letter spacing -0.01 em). Sizes: Welcome 24, Setup 24, Ready 26. */
internal fun titleStyle(size: Int): TextStyle =
    TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        lineHeight = (size + 6).sp,
        letterSpacing = (-0.01).em,
    )

/**
 * The small step label at the top left. The text is "Step n of 3". Only the style shows it in capitals, so TalkBack reads
 * the sentence-case text. This label is the one TalkBack text for the place in the flow: [StepDots] has no text.
 */
@Composable
internal fun StepLabel(
    phase: OnboardingPhase,
    modifier: Modifier = Modifier,
) {
    val text = stringResource(R.string.onb_page_indicator, phase.number, OnboardingPhase.COUNT)
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.04.em),
        color = NjTheme.roles.mu,
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
    )
}

/** Width of the dot of the current phase: a wider accent pill. */
private val DotCurrent = 18.dp

/** Size of the other dots. */
private val Dot = 6.dp

/** Three dots above the bottom button. The current phase is a wider accent pill, the others are small line-coloured dots. */
@Composable
internal fun StepDots(
    phase: OnboardingPhase,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    // StepLabel already reads "Step n of 3", so the dots are hidden from TalkBack.
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(Dot, Alignment.CenterHorizontally),
    ) {
        OnboardingPhase.entries.forEach { entry ->
            val current = entry == phase
            Box(
                Modifier
                    .height(Dot)
                    .width(if (current) DotCurrent else Dot)
                    .clip(NjShape.pill)
                    .background(if (current) roles.acc else roles.line),
            )
        }
    }
}

/** The bottom of each screen: the dots, then the buttons of the screen in [content]. */
@Composable
internal fun OnboardingFooter(
    phase: OnboardingPhase,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = NjSpacing.s)) {
        StepDots(phase, Modifier.padding(bottom = NjSpacing.m - 2.dp))
        content()
    }
}

/**
 * The scroll area between the step label and the footer. It fills the free height. When the [content] is smaller, it
 * is centred vertically ([centred]) or stays at the top. When the content is taller (large fonts, a small screen), it
 * scrolls, and nothing is cut.
 */
@Composable
internal fun ColumnScope.OnboardingBody(
    centred: Boolean,
    spacing: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport),
            verticalArrangement = Arrangement.spacedBy(spacing, if (centred) Alignment.CenterVertically else Alignment.Top),
            content = content,
        )
    }
}

/** Height of the glow area at the top of the screen (320 dp). */
private val GlowHeight = 320.dp

/** Radius of the glow (a radial gradient to the closest side of the glow area). */
private val GlowRadius = 200.dp

/** Distance from the top of the screen to the centre of the glow. */
private val GlowCentre = 60.dp

/**
 * A soft radial glow in [color] behind the top of the screen: the accent on Welcome, the OK green on Ready. The light
 * theme uses a lower alpha, because the same tint looks stronger on a light background. The glow has no semantics.
 */
@Composable
internal fun OnboardingGlow(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val alpha = if (NjTheme.roles.isDark) GLOW_ALPHA_DARK else GLOW_ALPHA_LIGHT
    val tint = color.copy(alpha = alpha)
    Box(
        modifier
            .fillMaxWidth()
            .height(GlowHeight)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(tint, Color.Transparent),
                        center = Offset(size.width / 2f, GlowCentre.toPx()),
                        radius = GlowRadius.toPx(),
                    ),
                )
            },
    )
}

private const val GLOW_ALPHA_DARK = 0.18f
private const val GLOW_ALPHA_LIGHT = 0.12f
