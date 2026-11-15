package dev.nightjar.app.domain.ingest

import dev.nightjar.app.data.repository.PackageLabelRepository
import dev.nightjar.app.domain.text.PackageLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Ingest fills the package label cache once per package, and a failed lookup is tried again. */
class PackageLabelRecorderTest {
    private class MemoryCache : PackageLabelRepository {
        val rows = linkedMapOf<String, String?>()

        override suspend fun label(packageName: String): String? = rows[packageName]

        override suspend fun put(
            packageName: String,
            label: String?,
        ) {
            rows[packageName] = label
        }

        override suspend fun all(): Map<String, String?> = rows.toMap()
    }

    @Test
    fun `each package is looked up once and null or blank names are skipped`() =
        runBlocking {
            val cache = MemoryCache()
            val lookups = mutableListOf<String?>()
            val labels =
                PackageLabels { pkg ->
                    lookups += pkg
                    if (pkg == "com.example.gone") null else "App $pkg"
                }
            val recorder = PackageLabelRecorder(cache, labels, Dispatchers.Unconfined)

            recorder.record("com.example.a", null, " ", "com.example.gone")
            recorder.record("com.example.a", "com.example.b")

            assertEquals(listOf("com.example.a", "com.example.gone", "com.example.b"), lookups)
            assertEquals(
                mapOf("com.example.a" to "App com.example.a", "com.example.gone" to null, "com.example.b" to "App com.example.b"),
                cache.rows,
            )
        }

    @Test
    fun `a failed lookup is tried again with the next message`() =
        runBlocking {
            val cache = MemoryCache()
            var fail = true
            val labels =
                PackageLabels { pkg ->
                    check(!fail) { "package manager is not ready" }
                    "App $pkg"
                }
            val recorder = PackageLabelRecorder(cache, labels, Dispatchers.Unconfined)

            assertThrows(IllegalStateException::class.java) { runBlocking { recorder.record("com.example.a") } }
            fail = false
            recorder.record("com.example.a")

            assertEquals(mapOf("com.example.a" to "App com.example.a"), cache.rows)
        }
}
