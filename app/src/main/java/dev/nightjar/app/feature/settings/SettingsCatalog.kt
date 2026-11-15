package dev.nightjar.app.feature.settings

import androidx.annotation.StringRes
import dev.nightjar.app.R

/** The settings home and its sub-screens. State survives rotation; Back from a sub-screen returns to the home. */
internal enum class SettingsPage {
    HOME,
    NOTIFICATIONS,
    DETECTION,
    SEVERITY,
    CELL,
    OUTGOING,
    PRIVACY,
    APPEARANCE,
}

/** The groups of the Settings home, in screen order. Each group is one card of rows. */
internal enum class SettingsGroup(
    @StringRes val title: Int,
) {
    APPEARANCE(R.string.settings_group_appearance),
    NOTIFICATIONS(R.string.settings_group_notifications),
    PROTECTION(R.string.nj_set_group_protection),
    DATA(R.string.nj_set_group_data),
    DIAGNOSTICS(R.string.nj_set_group_diagnostics),
    ABOUT(R.string.settings_group_about),
}

/**
 * Every settings row, in screen order. [page] is the screen the row shows on; a [SettingsPage.HOME] row with [opens]
 * opens that sub-screen. The search on the home looks at all rows, so a row on a sub-screen is found too. The row name
 * is its stable id: `SettingsCatalogTest` checks that each row of the old Settings screen is still here.
 */
internal enum class SettingsRow(
    val group: SettingsGroup,
    val page: SettingsPage,
    @StringRes val title: Int,
    @StringRes val supporting: Int? = null,
    val opens: SettingsPage? = null,
    val researcherOnly: Boolean = false,
) {
    // Appearance
    APPEARANCE(
        SettingsGroup.APPEARANCE,
        SettingsPage.HOME,
        R.string.settings_group_appearance,
        R.string.nj_set_row_appearance_sub,
        opens = SettingsPage.APPEARANCE,
    ),
    MODE(SettingsGroup.APPEARANCE, SettingsPage.HOME, R.string.settings_group_mode, R.string.settings_mode_sub),
    THEME(SettingsGroup.APPEARANCE, SettingsPage.APPEARANCE, R.string.settings_ui_theme),
    ACCENT(SettingsGroup.APPEARANCE, SettingsPage.APPEARANCE, R.string.nj_set_appearance_colour),
    DYNAMIC_COLOR(
        SettingsGroup.APPEARANCE,
        SettingsPage.APPEARANCE,
        R.string.nj_set_appearance_wallpaper,
        R.string.nj_set_appearance_wallpaper_sub,
    ),
    TRUE_BLACK(
        SettingsGroup.APPEARANCE,
        SettingsPage.APPEARANCE,
        R.string.nj_set_appearance_true_black,
        R.string.nj_set_appearance_true_black_sub,
    ),
    FLOATING_BAR(
        SettingsGroup.APPEARANCE,
        SettingsPage.APPEARANCE,
        R.string.nj_set_appearance_floating_bar,
        R.string.nj_set_appearance_floating_bar_sub,
    ),
    LIST_ANIMATIONS(
        SettingsGroup.APPEARANCE,
        SettingsPage.APPEARANCE,
        R.string.nj_set_appearance_list_animations,
        R.string.nj_set_appearance_list_animations_sub,
    ),
    BOUNCE(SettingsGroup.APPEARANCE, SettingsPage.APPEARANCE, R.string.nj_set_appearance_bounce, R.string.nj_set_appearance_bounce_sub),
    REDUCE_MOTION(
        SettingsGroup.APPEARANCE,
        SettingsPage.APPEARANCE,
        R.string.nj_set_appearance_reduce_motion,
        R.string.nj_set_appearance_reduce_motion_sub,
    ),
    HAPTICS(
        SettingsGroup.APPEARANCE,
        SettingsPage.APPEARANCE,
        R.string.nj_set_appearance_haptics,
        R.string.nj_set_appearance_haptics_sub,
    ),
    AUTO_REVIEW(SettingsGroup.APPEARANCE, SettingsPage.APPEARANCE, R.string.settings_ui_autoreview),

    // Notifications
    NOTIFY_SUSPICIOUS(SettingsGroup.NOTIFICATIONS, SettingsPage.HOME, R.string.settings_notify_suspicious),
    NOTIFY_NOTABLE(SettingsGroup.NOTIFICATIONS, SettingsPage.HOME, R.string.settings_notify_notable),
    NOTIFY_CB(SettingsGroup.NOTIFICATIONS, SettingsPage.HOME, R.string.settings_notify_cb),
    NOTIFY_MORE(
        SettingsGroup.NOTIFICATIONS,
        SettingsPage.HOME,
        R.string.nj_set_row_notify_more,
        R.string.nj_set_row_notify_more_sub,
        opens = SettingsPage.NOTIFICATIONS,
    ),
    NOTIFY_DIGEST(SettingsGroup.NOTIFICATIONS, SettingsPage.NOTIFICATIONS, R.string.settings_notify_digest),
    NOTIFY_RATE_LIMIT(SettingsGroup.NOTIFICATIONS, SettingsPage.NOTIFICATIONS, R.string.settings_notify_ratelimitmin),
    NOTIFY_CHANNELS(
        SettingsGroup.NOTIFICATIONS,
        SettingsPage.NOTIFICATIONS,
        R.string.settings_notify_channels,
        R.string.settings_open_channel,
    ),

    // Protection
    DETECTION(
        SettingsGroup.PROTECTION,
        SettingsPage.HOME,
        R.string.settings_cat_detection,
        R.string.settings_cat_detection_sub,
        opens = SettingsPage.DETECTION,
    ),
    SEVERITY(
        SettingsGroup.PROTECTION,
        SettingsPage.HOME,
        R.string.settings_severity_per_type,
        R.string.settings_cat_severity_sub,
        opens = SettingsPage.SEVERITY,
    ),
    CELL(
        SettingsGroup.PROTECTION,
        SettingsPage.HOME,
        R.string.settings_group_cb,
        R.string.settings_cat_cell_sub,
        opens = SettingsPage.CELL,
    ),
    OUTGOING(
        SettingsGroup.PROTECTION,
        SettingsPage.HOME,
        R.string.settings_cat_outgoing,
        R.string.settings_cat_outgoing_sub,
        opens = SettingsPage.OUTGOING,
    ),
    PRIVACY(
        SettingsGroup.PROTECTION,
        SettingsPage.HOME,
        R.string.settings_cat_privacy,
        R.string.settings_cat_privacy_sub,
        opens = SettingsPage.PRIVACY,
    ),
    DETECT_SILENT_COUNT(SettingsGroup.PROTECTION, SettingsPage.DETECTION, R.string.settings_detect_silent_count),
    DETECT_SILENT_WINDOW(SettingsGroup.PROTECTION, SettingsPage.DETECTION, R.string.settings_detect_silent_windowmin),
    DETECT_NEW_SENDER(SettingsGroup.PROTECTION, SettingsPage.DETECTION, R.string.settings_detect_silent_newsender),
    DETECT_ROAMING(SettingsGroup.PROTECTION, SettingsPage.DETECTION, R.string.settings_detect_silent_roaming),
    DETECT_SUPL_GRACE(SettingsGroup.PROTECTION, SettingsPage.DETECTION, R.string.settings_detect_supl_gracemin),
    KNOWN_SENDERS(SettingsGroup.PROTECTION, SettingsPage.DETECTION, R.string.settings_known_senders),
    CB_LOG(SettingsGroup.PROTECTION, SettingsPage.CELL, R.string.settings_cb_log),
    CB_ALL_CHANNELS(SettingsGroup.PROTECTION, SettingsPage.CELL, R.string.settings_cb_allchannels, researcherOnly = true),
    OUTGOING_ENABLED(SettingsGroup.PROTECTION, SettingsPage.OUTGOING, R.string.settings_outgoing_enabled),
    OUTGOING_LOG_VISIBLE(SettingsGroup.PROTECTION, SettingsPage.OUTGOING, R.string.settings_outgoing_logvisible),
    TRUSTED_APPS(SettingsGroup.PROTECTION, SettingsPage.OUTGOING, R.string.settings_trusted_apps),
    SENDING_APPS(SettingsGroup.PROTECTION, SettingsPage.OUTGOING, R.string.settings_sending_apps),
    APP_LOCK(SettingsGroup.PROTECTION, SettingsPage.PRIVACY, R.string.settings_privacy_applock),
    LOCK_TIMEOUT(SettingsGroup.PROTECTION, SettingsPage.PRIVACY, R.string.settings_privacy_locktimeout),
    MASK_NUMBERS(SettingsGroup.PROTECTION, SettingsPage.PRIVACY, R.string.settings_privacy_masknumbers),
    HIDE_IN_NOTIFICATIONS(SettingsGroup.PROTECTION, SettingsPage.PRIVACY, R.string.settings_privacy_hideinnotifications),
    BLOCK_SCREENSHOTS(SettingsGroup.PROTECTION, SettingsPage.PRIVACY, R.string.settings_privacy_blockscreenshots),

    // Data and storage
    RETENTION(SettingsGroup.DATA, SettingsPage.HOME, R.string.settings_data_retentiondays),
    EXPORT(SettingsGroup.DATA, SettingsPage.HOME, R.string.settings_export),
    DELETE_ALL(SettingsGroup.DATA, SettingsPage.HOME, R.string.settings_delete_all, R.string.settings_delete_all_sub),
    REMOVE_MODULE(SettingsGroup.DATA, SettingsPage.HOME, R.string.settings_remove_module, R.string.settings_turn_off_sub),

    // Diagnostics
    SHARE_DIAGNOSTICS(SettingsGroup.DIAGNOSTICS, SettingsPage.HOME, R.string.health_diagnostics),
    SAFE_MODE(SettingsGroup.DIAGNOSTICS, SettingsPage.HOME, R.string.settings_advanced_safemode, R.string.nj_set_row_safe_mode_sub),
    DEBUG_LOGGING(
        SettingsGroup.DIAGNOSTICS,
        SettingsPage.HOME,
        R.string.settings_advanced_debuglogging,
        R.string.settings_debug_window,
        researcherOnly = true,
    ),
    VIEW_LOGS(SettingsGroup.DIAGNOSTICS, SettingsPage.HOME, R.string.settings_view_logs, researcherOnly = true),
    AUDIT_HISTORY(SettingsGroup.DIAGNOSTICS, SettingsPage.HOME, R.string.settings_audit_history, researcherOnly = true),
    RESTART_PHONE(SettingsGroup.DIAGNOSTICS, SettingsPage.HOME, R.string.settings_restart_phone, researcherOnly = true),

    // About
    VERSION(SettingsGroup.ABOUT, SettingsPage.HOME, R.string.settings_version),
    BLIND_SPOTS(SettingsGroup.ABOUT, SettingsPage.HOME, R.string.settings_what_cant_see),
}

/**
 * The rows the user can see now. Researcher rows show only in Researcher mode; "Lock after" shows only when app lock is
 * on, as on the Privacy screen.
 */
internal fun visibleSettingsRows(
    researcher: Boolean,
    appLock: Boolean,
): List<SettingsRow> =
    SettingsRow.entries.filter { row ->
        (researcher || !row.researcherOnly) && (appLock || row != SettingsRow.LOCK_TIMEOUT)
    }

/** The words of a search [query], without empty parts. */
internal fun settingsSearchTerms(query: String): List<String> = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }

/**
 * The [rows] whose [title] or [description] contain every word of [query], case ignored, in the order of [rows]. A
 * blank query keeps all rows.
 */
internal fun <T> filterSettingsRows(
    rows: List<T>,
    query: String,
    title: (T) -> String,
    description: (T) -> String?,
): List<T> {
    val terms = settingsSearchTerms(query)
    if (terms.isEmpty()) return rows
    return rows.filter { row ->
        val rowTitle = title(row)
        val rowDescription = description(row).orEmpty()
        terms.all { term -> rowTitle.contains(term, ignoreCase = true) || rowDescription.contains(term, ignoreCase = true) }
    }
}

private val WHITESPACE = Regex("\\s+")
