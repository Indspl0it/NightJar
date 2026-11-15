package dev.nightjar.app.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.model.AuditEntry
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.feature.settings.pickerThemeMode
import dev.nightjar.app.feature.settings.previewDark
import dev.nightjar.app.feature.settings.previewSurfaceMode
import dev.nightjar.app.ui.theme.NjAccent
import dev.nightjar.app.ui.theme.NjSurfaceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The appearance settings. An old settings file without the new keys loads with the new defaults. */
class AppearanceSettingsTest {
    private lateinit var scope: CoroutineScope

    private class MemoryDataStore(
        initial: Preferences,
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private class NoAudit : AuditLog {
        override suspend fun record(
            action: AuditAction,
            actor: AuditActor,
            target: String?,
            before: String?,
            after: String?,
        ) = Unit

        override fun observe(limit: Int): Flow<List<AuditEntry>> = MutableStateFlow(emptyList())

        override suspend fun since(atWallMs: Long): List<AuditEntry> = emptyList()

        override suspend fun purgeBefore(cutoffWallMs: Long): Int = 0
    }

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun storeOver(initial: Preferences): SettingsStore =
        DataStoreSettingsStore(MemoryDataStore(initial), NoAudit(), Clock { 1_000L }, scope)

    @Test
    fun `new appearance defaults`() {
        val s = Settings()
        assertEquals(ThemeMode.SYSTEM, s.theme)
        assertEquals(NjAccent.NIGHT_BLUE, s.accent)
        assertFalse(s.dynamicColor)
        assertFalse(s.trueBlack)
        assertTrue(s.floatingBar)
        assertTrue(s.listAnimations)
        assertTrue(s.listBounce)
        assertFalse(s.reduceMotion)
        assertTrue(s.haptics)
    }

    @Test
    fun `old settings file without the new keys loads with the new defaults and keeps its values`() =
        runBlocking {
            val old =
                preferencesOf(
                    stringPreferencesKey("ui.theme") to "LIGHT",
                    booleanPreferencesKey("ui.dynamicColor") to true,
                    booleanPreferencesKey("ui.autoReview") to false,
                )
            val s = storeOver(old).current()
            assertEquals(ThemeMode.LIGHT, s.theme)
            assertTrue(s.dynamicColor)
            assertFalse(s.autoReview)
            assertEquals(NjAccent.NIGHT_BLUE, s.accent)
            assertFalse(s.trueBlack)
            assertTrue(s.floatingBar)
            assertTrue(s.listAnimations)
            assertTrue(s.listBounce)
            assertFalse(s.reduceMotion)
            assertTrue(s.haptics)
        }

    @Test
    fun `an unknown accent value falls back to night blue`() =
        runBlocking {
            val s = storeOver(preferencesOf(stringPreferencesKey("ui.accent") to "does_not_exist")).current()
            assertEquals(NjAccent.NIGHT_BLUE, s.accent)
        }

    @Test
    fun `each appearance setter changes one key and persists`() =
        runBlocking {
            val store = storeOver(preferencesOf())
            assertEquals(listOf("ui.accent"), store.setAccent(NjAccent.MOSS).map { it.key })
            assertEquals(listOf("ui.trueBlack"), store.setTrueBlack(true).map { it.key })
            assertEquals(listOf("ui.floatingBar"), store.setFloatingBar(false).map { it.key })
            assertEquals(listOf("ui.listAnimations"), store.setListAnimations(false).map { it.key })
            assertEquals(listOf("ui.listBounce"), store.setListBounce(false).map { it.key })
            assertEquals(listOf("ui.reduceMotion"), store.setReduceMotion(true).map { it.key })
            assertEquals(listOf("ui.haptics"), store.setHaptics(false).map { it.key })
            val s = store.current()
            assertEquals(NjAccent.MOSS, s.accent)
            assertTrue(s.trueBlack)
            assertFalse(s.floatingBar)
            assertFalse(s.listAnimations)
            assertFalse(s.listBounce)
            assertTrue(s.reduceMotion)
            assertFalse(s.haptics)
            assertTrue(store.setHaptics(false).isEmpty())
        }

    @Test
    fun `a write drops the removed blur key of an older install`() =
        runBlocking {
            // "Blur effects" is gone. An old file can still hold its key.
            val blurKey = booleanPreferencesKey("ui.blurEffects")
            val data = MemoryDataStore(preferencesOf(blurKey to false))
            val store = DataStoreSettingsStore(data, NoAudit(), Clock { 1_000L }, scope)
            assertEquals(listOf("ui.floatingBar"), store.setFloatingBar(false).map { it.key })
            assertNull(data.data.first()[blurKey])
            assertFalse(store.current().floatingBar)
        }

    @Test
    fun `an old black theme counts as true black and shows as dark in the picker`() {
        val s = Settings(theme = ThemeMode.BLACK)
        assertTrue(s.effectiveTrueBlack)
        assertEquals(ThemeMode.DARK, pickerThemeMode(ThemeMode.BLACK))
        assertEquals(ThemeMode.LIGHT, pickerThemeMode(ThemeMode.LIGHT))
        assertTrue(Settings(trueBlack = true).effectiveTrueBlack)
        assertFalse(Settings().effectiveTrueBlack)
    }

    @Test
    fun `preview uses black surfaces only in a dark theme`() {
        assertEquals(NjSurfaceMode.LIGHT, previewSurfaceMode(Settings(theme = ThemeMode.LIGHT, trueBlack = true), systemDark = true))
        assertEquals(NjSurfaceMode.BLACK, previewSurfaceMode(Settings(theme = ThemeMode.SYSTEM, trueBlack = true), systemDark = true))
        assertEquals(NjSurfaceMode.LIGHT, previewSurfaceMode(Settings(theme = ThemeMode.SYSTEM, trueBlack = true), systemDark = false))
        assertEquals(NjSurfaceMode.DARK, previewSurfaceMode(Settings(theme = ThemeMode.DARK), systemDark = false))
        assertTrue(previewDark(ThemeMode.BLACK, systemDark = false))
    }
}
