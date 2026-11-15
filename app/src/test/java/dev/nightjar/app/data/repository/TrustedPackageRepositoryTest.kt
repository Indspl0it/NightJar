package dev.nightjar.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.RoomAuditLog
import dev.nightjar.app.data.db.NightjarDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B4, the store link: a trust is one row that the Apps tab list (`observeAll`) and the severity snapshot (`all`)
 * both read, and every change has one audit row. Example packages only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrustedPackageRepositoryTest {
    private lateinit var db: NightjarDatabase
    private lateinit var repository: RoomTrustedPackageRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, NightjarDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val clock = Clock { now }
        repository = RoomTrustedPackageRepository(db.trustedPackageDao(), RoomAuditLog(db.auditDao(), clock), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a trust is listed and read by the severity snapshot`() =
        runBlocking {
            repository.add("dev.example.tester", null)
            assertEquals(listOf("dev.example.tester"), repository.observeAll().first().map { it.packageName })
            assertEquals(listOf("dev.example.tester"), repository.all().map { it.packageName })
            assertTrue(repository.isTrusted("dev.example.tester"))
        }

    @Test
    fun `trusting the same app twice keeps one row and the newer label`() =
        runBlocking {
            repository.add("dev.example.tester", null)
            now = 2_000L
            repository.add("dev.example.tester", "Tester")
            val rows = repository.all()
            assertEquals(1, rows.size)
            assertEquals("Tester", rows.single().label)
        }

    @Test
    fun `add and remove each write one audit row`() =
        runBlocking {
            repository.add("dev.example.tester", "Tester")
            assertTrue(repository.remove("dev.example.tester"))
            assertFalse(repository.remove("dev.example.tester"))
            assertFalse(repository.isTrusted("dev.example.tester"))
            val actions = db.auditDao().since(0L).map { it.action }
            assertEquals(listOf(AuditAction.TRUST_APP_ADD.name, AuditAction.TRUST_APP_REMOVE.name), actions.sorted())
        }

    @Test
    fun `a blank package is not stored`() =
        runBlocking {
            repository.add(" ", null)
            assertTrue(repository.all().isEmpty())
        }
}
