package dev.nightjar.app.domain.ingest

import dev.nightjar.app.data.repository.PackageLabelRepository
import dev.nightjar.app.domain.text.PackageLabels
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills the package label cache (`package_label`) when ingest sees a consumer or calling
 * package, so the list rows can name the app (`consumerPackageLabel`, `callingPackageLabel`).
 *
 * The label comes from PackageManager ([PackageLabels]) on [io], never on the main thread. Each package is looked up
 * once per process: a later app update shows its new label after the next start. A package that is not installed is
 * stored with no label, and the UI then shows the package name.
 */
@Singleton
class PackageLabelRecorder(
    private val cache: PackageLabelRepository,
    private val labels: PackageLabels,
    private val io: CoroutineDispatcher,
) {
    @Inject
    constructor(cache: PackageLabelRepository, labels: PackageLabels) : this(cache, labels, Dispatchers.IO)

    private val recorded = ConcurrentHashMap.newKeySet<String>()

    /** Caches the label of each package in [packages] that this process has not cached yet. Null and blank are skipped. */
    suspend fun record(vararg packages: String?) {
        for (pkg in packages) {
            if (pkg.isNullOrBlank() || !recorded.add(pkg)) continue
            var cached = false
            try {
                val label = withContext(io) { labels.labelOf(pkg) }
                cache.put(pkg, label)
                cached = true
            } finally {
                // The next message from this package tries again.
                if (!cached) recorded.remove(pkg)
            }
        }
    }
}
