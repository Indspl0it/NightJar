package dev.nightjar.app.feature.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.data.repository.WeekCounts
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict
import java.time.LocalDate

// Previews of onboarding Home (moved out of HomeScreen.kt: detekt TooManyFunctions).

@Composable
private fun HomePreview(
    theme: ThemeMode,
    status: HomeStatus,
) {
    PreviewTheme(themeMode = theme) {
        HomeContent(
            state = previewState(status),
            attentionCount = 2,
            attentionAt = { index -> if (index == 0) previewBlockedRow() else previewHeldRow() },
            snackbar = SnackbarHostState(),
            onRefresh = {},
            onOpenHealth = {},
            onRunSelfTest = {},
            onSeeAll = {},
            onOpenMessage = {},
            onOpenClass = {},
            onOpenFilter = {},
            onRelease = {},
            onBlockSimilar = {},
            onSetSafeMode = {},
            onHelp = {},
        )
    }
}

@Preview(name = "Home protected dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, heightDp = 1500)
@Composable
private fun HomeProtectedDarkPreview() = HomePreview(ThemeMode.DARK, HomeStatus.PROTECTED)

@Preview(name = "Home protected light", heightDp = 1500)
@Composable
private fun HomeProtectedLightPreview() = HomePreview(ThemeMode.LIGHT, HomeStatus.PROTECTED)

@Preview(name = "Home blocking paused dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, heightDp = 1500)
@Composable
private fun HomePausedDarkPreview() = HomePreview(ThemeMode.DARK, HomeStatus.BLOCKING_PAUSED)

@Preview(name = "Home blocking paused light", heightDp = 1500)
@Composable
private fun HomePausedLightPreview() = HomePreview(ThemeMode.LIGHT, HomeStatus.BLOCKING_PAUSED)

@Preview(name = "Home monitor only dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, heightDp = 1500)
@Composable
private fun HomeMonitorDarkPreview() = HomePreview(ThemeMode.DARK, HomeStatus.MONITOR_ONLY)

@Preview(name = "Home monitor only light", heightDp = 1500)
@Composable
private fun HomeMonitorLightPreview() = HomePreview(ThemeMode.LIGHT, HomeStatus.MONITOR_ONLY)

@Preview(name = "Home not active dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, heightDp = 1500)
@Composable
private fun HomeNotActiveDarkPreview() = HomePreview(ThemeMode.DARK, HomeStatus.NOT_ACTIVE)

@Preview(name = "Home not active light", heightDp = 1500)
@Composable
private fun HomeNotActiveLightPreview() = HomePreview(ThemeMode.LIGHT, HomeStatus.NOT_ACTIVE)

@Preview(name = "Home safe mode dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, heightDp = 1500)
@Composable
private fun HomeSafeModeDarkPreview() = HomePreview(ThemeMode.DARK, HomeStatus.SAFE_MODE)

@Preview(name = "Home safe mode light", heightDp = 1500)
@Composable
private fun HomeSafeModeLightPreview() = HomePreview(ThemeMode.LIGHT, HomeStatus.SAFE_MODE)

@Preview(name = "Home not supported dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, heightDp = 1500)
@Composable
private fun HomeUnsupportedDarkPreview() = HomePreview(ThemeMode.DARK, HomeStatus.UNSUPPORTED)

@Preview(name = "Home not supported light", heightDp = 1500)
@Composable
private fun HomeUnsupportedLightPreview() = HomePreview(ThemeMode.LIGHT, HomeStatus.UNSUPPORTED)

private fun previewState(status: HomeStatus): HomeUiState {
    val today = LocalDate.now()
    val counts = listOf(4, 6, 3, 9, 7, 12, 6)
    val title =
        when (status) {
            HomeStatus.PROTECTED -> "Protected"
            HomeStatus.BLOCKING_PAUSED -> "Blocking paused"
            HomeStatus.MONITOR_ONLY -> "Blocking off"
            HomeStatus.NOT_ACTIVE -> "Not active"
            HomeStatus.SAFE_MODE -> "Safe mode is on"
            HomeStatus.UNSUPPORTED -> "Not supported"
        }
    val body =
        when (status) {
            HomeStatus.PROTECTED, HomeStatus.BLOCKING_PAUSED -> ""
            HomeStatus.MONITOR_ONLY -> "NightJar records messages but does not block or hold them."
            HomeStatus.NOT_ACTIVE -> "The module does not run. Turn it on in Vector or LSPosed."
            HomeStatus.SAFE_MODE -> "NightJar does not block messages."
            HomeStatus.UNSUPPORTED -> "NightJar cannot read messages on this phone."
        }
    return HomeUiState(
        loading = false,
        heroTitle = title,
        heroBody = body,
        status = status,
        week = WeekCounts(hidden = 47, suspicious = 3, onHold = 2, blocked = 5),
        days =
            counts.mapIndexed { index, count ->
                val date = today.minusDays((counts.size - 1 - index).toLong())
                DayBar(label = date.toString(), count = count, filter = dayFilter(date))
            },
        byType =
            listOf(
                TypeRow(ClassId.SILENT, "Silent SMS (Type 0)", 12),
                TypeRow(ClassId.MWI, "Voicemail indicator", 9),
                TypeRow(ClassId.WAP_UNREADABLE, "Malformed WAP push", 1),
            ),
    )
}

private fun previewRow(
    id: Long,
    classId: ClassId,
    title: String,
    severity: Severity,
    quarantined: Boolean,
    fate: Fate,
    slot: Int,
): MessageListItem =
    MessageListItem(
        id = id,
        eventId = "e$id",
        receivedAtWall = System.currentTimeMillis(),
        classId = classId,
        severity = severity,
        title = title,
        originator = "+10000004821",
        textPreview = "",
        fate = fate,
        verdict = Verdict.ALLOW,
        isQuarantined = quarantined,
        isReviewed = false,
        isStarred = false,
        direction = Direction.IN,
        subId = slot + 1,
        slot = slot,
        partsHave = 1,
        partsTotal = 1,
        consumerPackage = null,
        consumerPackageLabel = null,
        isReplay = false,
    )

// The Recommended preset blocks a malformed WAP push and holds a phone unlock message.
private fun previewHeldRow(): MessageListItem =
    previewRow(
        1,
        ClassId.ME_DEPERSO,
        "Phone unlock message (de-personalization)",
        Severity.HIGH,
        quarantined = true,
        fate = Fate.QUARANTINED,
        slot = 0,
    )

private fun previewBlockedRow(): MessageListItem =
    previewRow(
        2,
        ClassId.WAP_UNREADABLE,
        "Malformed WAP push: WSP header length is wrong",
        Severity.HIGH,
        quarantined = false,
        fate = Fate.BLOCKED,
        slot = 1,
    )
