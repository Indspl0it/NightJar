package dev.nightjar.app.feature.protection

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `HiltWorkerFactory` only builds a worker whose constructor is `@AssistedInject` with `@Assisted`
 * `(Context, WorkerParameters)` parameters.
 *
 * Without them the reflective default factory looked for a `(Context, WorkerParameters)` constructor, failed the work
 * with "Could not instantiate", and NOT_ACTIVE was never posted and the channels were never recreated after a
 * restore. Annotations are retained at runtime, so the shape is checked without a Hilt test component.
 */
class BootCheckWorkerTest {
    @Test
    fun `Hilt can construct every worker`() {
        val workers =
            listOf(
                BootCheckWorker::class.java,
                dev.nightjar.app.work.RetentionWorker::class.java,
                dev.nightjar.app.work.IngestSweepWorker::class.java,
                dev.nightjar.app.work.StagingDrainWorker::class.java,
            )
        for (type in workers) {
            val constructor =
                requireNotNull(
                    type.constructors.singleOrNull { it.isAnnotationPresent(AssistedInject::class.java) },
                ) { "${type.simpleName} needs an @AssistedInject constructor" }

            val parameters = constructor.parameterAnnotations
            assertTrue("${type.simpleName}: @Assisted context", parameters[0].any { it.annotationClass == Assisted::class })
            assertTrue("${type.simpleName}: @Assisted params", parameters[1].any { it.annotationClass == Assisted::class })
        }
    }
}
