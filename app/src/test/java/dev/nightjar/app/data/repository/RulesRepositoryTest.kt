package dev.nightjar.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nightjar.app.core.settings.DataStoreSettingsStore
import dev.nightjar.app.data.audit.RoomAuditLog
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.db.RuleEntity
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Preset
import dev.nightjar.rules.Presets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stored rules are read back into the engine's model; an unreadable row must not become a rule that matches everything. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RulesRepositoryTest {
    private lateinit var db: NightjarDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: RoomRulesRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NightjarDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore =
            dev.nightjar.app.core.settings
                .InMemoryPreferencesDataStore()
        val clock = Clock { 1_000L }
        val audit = RoomAuditLog(db.auditDao(), clock)
        repository =
            RoomRulesRepository(
                db,
                db.ruleDao(),
                db.classActionDao(),
                db.knownOriginatorDao(),
                db.ruleMetaDao(),
                DataStoreSettingsStore(dataStore, audit, clock, scope).also { settings = it },
                audit,
                clock,
            )
    }

    private lateinit var settings: DataStoreSettingsStore

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun row(
        id: Long,
        conditionsJson: String,
    ) = RuleEntity(
        id = id,
        priority = id.toInt(),
        enabled = true,
        name = "rule $id",
        conditionsJson = conditionsJson,
        action = "BLOCK",
        origin = "USER",
        createdAt = 1L,
        updatedAt = 1L,
        matchCount = 0,
        lastMatchAt = null,
    )

    @Test
    fun `a rule whose conditions cannot be read is switched off instead of matching everything`() =
        runBlocking {
            db.ruleDao().upsertAll(listOf(row(1, "not json at all"), row(2, """{"originator":{"op":"nonsense"}}""")))

            val rules = repository.snapshot().rules

            assertEquals(2, rules.size)
            assertTrue("a BLOCK rule with unreadable conditions must be disabled", rules.none { it.enabled() })
        }

    @Test
    fun `a readable rule keeps its enabled flag`() =
        runBlocking {
            db.ruleDao().upsertAll(listOf(row(1, "{}")))

            val rule = repository.snapshot().rules.single()

            assertTrue(rule.enabled())
            assertFalse(rule.name().isEmpty())
        }

    @Test
    fun `a change from Strict to Monitor leaves no held class`() =
        runBlocking {
            repository.applyPreset(Preset.STRICT)
            // Strict holds the voicemail indicator.
            assertEquals(Verdict.QUARANTINE, repository.snapshot().classAction(ClassId.MWI))

            repository.applyPreset(Preset.MONITOR)

            val config = repository.snapshot()
            assertEquals(Preset.MONITOR, config.preset)
            assertTrue(
                "Monitor must not keep a class action from Strict",
                config.classActions.values.none { it == Verdict.QUARANTINE },
            )
        }

    @Test
    fun `a new install gets Recommended once`() =
        runBlocking {
            assertEquals(Preset.MONITOR, repository.snapshot().preset)
            val seeded = repository.applyDefaultPresetOnce()
            assertEquals(Preset.RECOMMENDED, seeded?.preset)
            assertEquals(Presets.classActions(Preset.RECOMMENDED), repository.snapshot().classActions)
            assertNull(repository.applyDefaultPresetOnce())
        }

    @Test
    fun `an install with a stored preset keeps it`() =
        runBlocking {
            repository.applyPreset(Preset.MONITOR)
            assertNull(repository.applyDefaultPresetOnce())
            assertEquals(Preset.MONITOR, repository.snapshot().preset)
            repository.setClassAction(ClassId.WAP_MMS, Verdict.BLOCK)
            assertNull(repository.applyDefaultPresetOnce())
            assertEquals(Preset.CUSTOM, repository.snapshot().preset)
        }

    @Test
    fun `the preset after setup is Recommended on a new install and the stored preset else`() =
        runBlocking {
            // A new install reads Recommended before applyDefaultPresetOnce runs, and the read changes nothing.
            assertEquals(Preset.RECOMMENDED, repository.presetAfterSetup())
            assertEquals(Preset.MONITOR, repository.snapshot().preset)
            repository.applyDefaultPresetOnce()
            assertEquals(Preset.RECOMMENDED, repository.presetAfterSetup())
            repository.applyPreset(Preset.STRICT)
            assertEquals(Preset.STRICT, repository.presetAfterSetup())
            repository.setClassAction(ClassId.WAP_MMS, Verdict.BLOCK)
            assertEquals(Preset.CUSTOM, repository.presetAfterSetup())
        }

    @Test
    fun `a preset replaces a class action that the user set`() =
        runBlocking {
            repository.setClassAction(ClassId.WAP_MMS, Verdict.BLOCK)
            assertEquals(Verdict.BLOCK, repository.snapshot().classAction(ClassId.WAP_MMS))

            repository.applyPreset(Preset.RECOMMENDED)

            val config = repository.snapshot()
            assertEquals(Preset.RECOMMENDED, config.preset)
            assertEquals(Verdict.ALLOW, config.classAction(ClassId.WAP_MMS))
            // The class actions are exactly those of Recommended.
            assertEquals(Presets.classActions(Preset.RECOMMENDED), config.classActions)
        }

    @Test
    fun `Strict turns notify notable on and leaving Strict restores the old value`() =
        runBlocking {
            assertFalse(settings.current().notifyNotable)
            repository.applyPreset(Preset.STRICT)
            assertTrue(settings.current().notifyNotable)
            repository.applyPreset(Preset.RECOMMENDED)
            assertFalse(settings.current().notifyNotable)
        }

    @Test
    fun `reorder moves only user rules and keeps the preset priorities`() =
        runBlocking {
            repository.applyPreset(Preset.RECOMMENDED)
            val presetBefore = repository.snapshot().rules.filter { it.origin() == dev.nightjar.rules.RuleOrigin.PRESET }
            val a = repository.createRule("a", Verdict.BLOCK, dev.nightjar.rules.Conditions.ANY).first.id
            val b = repository.createRule("b", Verdict.BLOCK, dev.nightjar.rules.Conditions.ANY).first.id
            val priorities = repository.snapshot().rules.associate { it.id() to it.priority() }

            repository.reorder(listOf(b, a) + presetBefore.map { it.id() })

            val after = repository.snapshot().rules.associate { it.id() to it.priority() }
            assertEquals(priorities[a], after[b])
            assertEquals(priorities[b], after[a])
            presetBefore.forEach { assertEquals(it.priority(), after[it.id()]) }
        }
}
