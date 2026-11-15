package dev.nightjar.app.domain.known

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure logic of the trust picker. Example packages only. */
class AppPickerTest {
    private val notes = InstalledApp("dev.example.notes", "notes", system = false)
    private val alarm = InstalledApp("dev.example.alarm", "Alarm", system = false)
    private val phone = InstalledApp("dev.example.phone", "Phone services", system = true)
    private val twin = InstalledApp("dev.example.alarm2", "Alarm", system = false)
    private val apps = AppPicker.sorted(listOf(notes, phone, twin, alarm))

    @Test
    fun `apps sort by label without case, then by package`() {
        assertEquals(listOf(alarm, twin, notes, phone), apps)
    }

    @Test
    fun `the filter chips split user and system apps`() {
        assertEquals(listOf(alarm, twin, notes), AppPicker.visible(apps, "", AppFilter.USER))
        assertEquals(listOf(phone), AppPicker.visible(apps, "", AppFilter.SYSTEM))
        assertEquals(apps, AppPicker.visible(apps, "", AppFilter.ALL))
    }

    @Test
    fun `the search matches the label or the package without case`() {
        assertEquals(listOf(phone), AppPicker.visible(apps, "PHONE", AppFilter.ALL))
        assertEquals(listOf(twin), AppPicker.visible(apps, "alarm2", AppFilter.ALL))
        assertEquals(listOf(notes), AppPicker.visible(apps, "  not ", AppFilter.USER))
        assertTrue(AppPicker.visible(apps, "phone", AppFilter.USER).isEmpty())
    }

    @Test
    fun `select all, invert and clear act on the rows on the screen only`() {
        val shown = AppPicker.visible(apps, "", AppFilter.USER)
        val start = setOf(phone.packageName, notes.packageName)
        assertEquals(start + alarm.packageName + twin.packageName, AppPicker.selectAll(start, shown))
        assertEquals(setOf(phone.packageName, alarm.packageName, twin.packageName), AppPicker.invert(start, shown))
        assertEquals(setOf(phone.packageName), AppPicker.clear(start, shown))
    }

    @Test
    fun `toggle adds and removes one package`() {
        val once = AppPicker.toggle(emptySet(), notes.packageName)
        assertEquals(setOf(notes.packageName), once)
        assertEquals(emptySet<String>(), AppPicker.toggle(once, notes.packageName))
    }

    @Test
    fun `changes add the new checks and remove the cleared ones`() {
        val changes = AppPicker.changes(trusted = setOf("a", "b"), selected = setOf("b", "c"))
        assertEquals(setOf("c"), changes.add)
        assertEquals(setOf("a"), changes.remove)
        assertEquals(2, changes.count)
        assertTrue(AppPicker.changes(setOf("a"), setOf("a")).isEmpty)
    }
}
