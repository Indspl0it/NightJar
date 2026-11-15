package dev.nightjar.app.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The database key is only minted where it can be stored, and a half-written key file is a loud error instead of
 * a silent new key (which would orphan the encrypted database).
 *
 * Robolectric only for `android.system.Os.chmod`, which the key install step calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DbKeyStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `an existing key is returned unchanged`() {
        val directory = temp.newFolder("keys")
        val key = ByteArray(DbKeyStore.KEY_BYTES) { it.toByte() }
        File(directory, DbKeyStore.DB_KEY).writeBytes(key)

        assertArrayEquals(key, DbKeyStore(directory).loadOrCreate(DbKeyStore.DB_KEY))
    }

    @Test
    fun `a missing key is generated at the right length`() {
        val directory = temp.newFolder("keys")
        val key = DbKeyStore(directory).loadOrCreate(DbKeyStore.DB_KEY)

        assertEquals(DbKeyStore.KEY_BYTES, key.size)
        assertTrue(File(directory, DbKeyStore.DB_KEY).isFile)
        // Generation is stable: the second call reads the same bytes back.
        assertArrayEquals(key, DbKeyStore(directory).loadOrCreate(DbKeyStore.DB_KEY))
    }

    @Test
    fun `a truncated key file is an error instead of a fresh key`() {
        val directory = temp.newFolder("keys")
        File(directory, DbKeyStore.DB_KEY).writeBytes(ByteArray(DbKeyStore.KEY_BYTES - 1))

        val error =
            assertThrows(IllegalStateException::class.java) {
                DbKeyStore(directory).loadOrCreate(DbKeyStore.DB_KEY)
            }
        assertTrue(error.message.orEmpty().contains("expected ${DbKeyStore.KEY_BYTES}"))
    }

    @Test
    fun `the key directory is created when it does not exist`() {
        val directory = File(temp.root, "nested/keys")
        val key = DbKeyStore(directory).loadOrCreate(DbKeyStore.DB_KEY)

        assertEquals(DbKeyStore.KEY_BYTES, key.size)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun `a missing credential-protected directory does not mint a key`() {
        // A path below a *file* cannot be created, which is what the CE directory looks like before the first unlock.
        val blocker = temp.newFile("blocker")
        val directory = File(blocker, "keys")

        assertFalse(directory.isDirectory)
        assertThrows(IllegalStateException::class.java) { DbKeyStore(directory).loadOrCreate(DbKeyStore.DB_KEY) }
    }
}
