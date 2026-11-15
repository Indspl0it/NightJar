package dev.nightjar.app.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsCatalogTest {
    private data class Row(
        val title: String,
        val description: String?,
    )

    private val rows =
        listOf(
            Row("App lock", null),
            Row("Mask numbers in lists", null),
            Row("Safe mode", "NightJar blocks no messages. NightJar still records them."),
            Row("Appearance", "Theme, colour, surfaces and motion"),
            Row("Lock after", null),
        )

    private fun filter(query: String) = filterSettingsRows(rows, query, Row::title, Row::description).map { it.title }

    @Test
    fun `every row of the old Settings screen is still in the catalogue`() {
        val ids = SettingsRow.entries.map { it.name }.toSet()
        val missing = OLD_ROW_IDS.filterNot { it in ids }
        assertTrue(missing.isEmpty(), "rows missing: $missing")
    }

    @Test
    fun `every row on a sub-screen has a home row in the same group that opens it`() {
        visibleSettingsRows(researcher = true, appLock = true)
            .filter { it.page != SettingsPage.HOME }
            .forEach { row ->
                val opener = SettingsRow.entries.firstOrNull { it.page == SettingsPage.HOME && it.opens == row.page }
                assertNotNull(opener, "no home row opens ${row.page} for $row")
                assertEquals(row.group, opener?.group, "$row and its opener $opener are in different groups")
            }
    }

    @Test
    fun `every sub-screen has exactly one opener on the home`() {
        SettingsPage.entries.filter { it != SettingsPage.HOME }.forEach { page ->
            val openers = SettingsRow.entries.filter { it.opens == page }
            assertEquals(1, openers.size, "openers of $page: $openers")
            assertEquals(SettingsPage.HOME, openers.single().page)
        }
    }

    @Test
    fun `a row that opens a sub-screen is on the home and does not open the home`() {
        SettingsRow.entries.filter { it.opens != null }.forEach { row ->
            assertEquals(SettingsPage.HOME, row.page, "$row")
            assertTrue(row.opens != SettingsPage.HOME, "$row")
        }
    }

    @Test
    fun `every group has a home row in Simple mode`() {
        val home = visibleSettingsRows(researcher = false, appLock = false).filter { it.page == SettingsPage.HOME }
        SettingsGroup.entries.forEach { group ->
            assertTrue(home.any { it.group == group }, "group $group has no row")
        }
    }

    @Test
    fun `Researcher rows show only in Researcher mode`() {
        val simple = visibleSettingsRows(researcher = false, appLock = true)
        val researcher = visibleSettingsRows(researcher = true, appLock = true)
        val researcherOnly = SettingsRow.entries.filter { it.researcherOnly }
        assertTrue(researcherOnly.isNotEmpty())
        researcherOnly.forEach { row ->
            assertFalse(row in simple, "$row shows in Simple mode")
            assertTrue(row in researcher, "$row is missing in Researcher mode")
        }
        assertEquals(SettingsRow.entries.size, researcher.size)
    }

    @Test
    fun `Lock after shows only when app lock is on`() {
        assertFalse(SettingsRow.LOCK_TIMEOUT in visibleSettingsRows(researcher = true, appLock = false))
        assertTrue(SettingsRow.LOCK_TIMEOUT in visibleSettingsRows(researcher = true, appLock = true))
    }

    @Test
    fun `the old Data and Advanced rows are on the home`() {
        listOf(
            SettingsRow.RETENTION,
            SettingsRow.EXPORT,
            SettingsRow.SHARE_DIAGNOSTICS,
            SettingsRow.DELETE_ALL,
            SettingsRow.REMOVE_MODULE,
            SettingsRow.SAFE_MODE,
            SettingsRow.DEBUG_LOGGING,
            SettingsRow.VIEW_LOGS,
            SettingsRow.AUDIT_HISTORY,
            SettingsRow.RESTART_PHONE,
        ).forEach { assertEquals(SettingsPage.HOME, it.page, "$it") }
    }

    @Test
    fun `a blank query keeps all rows in order`() {
        assertEquals(rows.map { it.title }, filter(""))
        assertEquals(rows.map { it.title }, filter("   "))
    }

    @Test
    fun `the filter matches the title with case ignored`() {
        assertEquals(listOf("Mask numbers in lists"), filter("mAsK"))
    }

    @Test
    fun `the filter matches part of a word in the title or the description`() {
        // "blocks" in the safe mode description contains "lock".
        assertEquals(listOf("App lock", "Safe mode", "Lock after"), filter("LOCK"))
    }

    @Test
    fun `the filter matches the description`() {
        assertEquals(listOf("Safe mode"), filter("records"))
        assertEquals(listOf("Appearance"), filter("colour"))
    }

    @Test
    fun `every word must match the title or the description`() {
        assertEquals(listOf("Safe mode"), filter("safe blocks"))
        assertEquals(listOf("App lock"), filter("  app   lock "))
        assertEquals(listOf("Appearance"), filter("app motion"))
        assertEquals(emptyList<String>(), filter("safe motion"))
    }

    @Test
    fun `a query with no match gives no rows`() {
        assertEquals(emptyList<String>(), filter("bluetooth"))
    }

    @Test
    fun `search terms are the words of the query`() {
        assertEquals(listOf("lock", "after"), settingsSearchTerms("  lock\tafter \n"))
        assertTrue(settingsSearchTerms(" ").isEmpty())
    }

    private companion object {
        /** Each row of the old Settings screen, including the category rows whose sub-screen still exists. */
        val OLD_ROW_IDS =
            listOf(
                // Home
                "MODE",
                "DETECTION",
                "SEVERITY",
                "CELL",
                "OUTGOING",
                "PRIVACY",
                "APPEARANCE",
                "VERSION",
                "BLIND_SPOTS",
                // Notifications
                "NOTIFY_SUSPICIOUS",
                "NOTIFY_NOTABLE",
                "NOTIFY_CB",
                "NOTIFY_DIGEST",
                "NOTIFY_RATE_LIMIT",
                "NOTIFY_CHANNELS",
                // Detection
                "DETECT_SILENT_COUNT",
                "DETECT_SILENT_WINDOW",
                "DETECT_NEW_SENDER",
                "DETECT_ROAMING",
                "DETECT_SUPL_GRACE",
                "KNOWN_SENDERS",
                // Cell broadcasts
                "CB_LOG",
                "CB_ALL_CHANNELS",
                // Outgoing messages
                "OUTGOING_ENABLED",
                "OUTGOING_LOG_VISIBLE",
                "TRUSTED_APPS",
                // Privacy and security
                "APP_LOCK",
                "LOCK_TIMEOUT",
                "MASK_NUMBERS",
                "HIDE_IN_NOTIFICATIONS",
                "BLOCK_SCREENSHOTS",
                // Appearance
                "THEME",
                "ACCENT",
                "DYNAMIC_COLOR",
                "TRUE_BLACK",
                "FLOATING_BAR",
                "LIST_ANIMATIONS",
                "BOUNCE",
                "REDUCE_MOTION",
                "HAPTICS",
                "AUTO_REVIEW",
                // Data and export
                "RETENTION",
                "EXPORT",
                "SHARE_DIAGNOSTICS",
                "DELETE_ALL",
                "REMOVE_MODULE",
                // Advanced
                "SAFE_MODE",
                "DEBUG_LOGGING",
                "VIEW_LOGS",
                "AUDIT_HISTORY",
                "RESTART_PHONE",
            )
    }
}
