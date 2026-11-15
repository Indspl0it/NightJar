package dev.nightjar.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

/** The staging database keeps only the newest 500 events and drains in arrival order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StagingStoreTest {
    private lateinit var db: StagingDatabase
    private lateinit var store: StagingStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StagingDatabase::class.java).allowMainThreadQueries().build()
        store = StagingStore(db.stagedEventDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `staged events are read oldest first`() =
        runBlocking {
            store.stage("b", "{\"n\":2}", 20)
            store.stage("a", "{\"n\":1}", 10)
            assertEquals(listOf("a", "b"), store.peek().map { it.eventId })
        }

    @Test
    fun `the table is capped at 500 events`() =
        runBlocking {
            repeat(StagingDatabase.MAX_EVENTS + 25) { i -> store.stage("e$i", "{}", i.toLong()) }
            assertEquals(StagingDatabase.MAX_EVENTS, store.count())
            val oldest = store.peek(1).single()
            assertEquals("e25", oldest.eventId)
        }

    @Test
    fun `a repeated event id replaces the row`() =
        runBlocking {
            store.stage("dup", "{\"v\":1}", 1)
            store.stage("dup", "{\"v\":2}", 2)
            assertEquals(1, store.count())
            assertEquals("{\"v\":2}", store.peek().single().json)
        }

    @Test
    fun `an older revision does not overwrite a newer staged one`() =
        runBlocking {
            store.stage("rev", "{\"revision\":3}", 1)
            store.stage("rev", "{\"revision\":2}", 2)
            assertEquals("{\"revision\":3}", store.peek().single().json)
            store.stage("rev", "{\"revision\":4}", 3)
            assertEquals("{\"revision\":4}", store.peek().single().json)
        }

    @Test
    fun `removal only deletes the given ids`() =
        runBlocking {
            store.stage("a", "{}", 1)
            store.stage("b", "{}", 2)
            store.remove(listOf("a"))
            assertEquals(listOf("b"), store.peek().map { it.eventId })
            assertEquals(0, store.remove(emptyList()))
        }

    @Test
    fun `clear empties the staging table`() =
        runBlocking {
            store.stage("a", "{}", 1)
            store.clear()
            assertEquals(0, store.count())
            assertFalse(store.peek().isNotEmpty())
            assertTrue(store.peek().isEmpty())
        }

    @Test
    fun `peek honours the batch limit`() =
        runBlocking {
            repeat(10) { i -> store.stage("e$i", "{}", i.toLong()) }
            assertEquals(4, store.peek(4).size)
        }
}
