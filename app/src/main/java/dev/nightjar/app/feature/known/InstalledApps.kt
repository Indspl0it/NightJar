package dev.nightjar.app.feature.known

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.nightjar.app.domain.known.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The installed apps for the trust picker. The app has QUERY_ALL_PACKAGES, so every app is visible. */
interface InstalledAppSource {
    /** Reads the installed apps off the main thread. NightJar itself is not in the list. */
    suspend fun load(): List<InstalledApp>
}

@Singleton
class PackageManagerAppSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : InstalledAppSource {
        override suspend fun load(): List<InstalledApp> =
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm
                    .getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
                    .asSequence()
                    .filter { it.packageName != context.packageName }
                    .map { info ->
                        val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                        InstalledApp(
                            packageName = info.packageName,
                            label = label?.takeIf { it.isNotBlank() } ?: info.packageName,
                            system = info.flags and SYSTEM_FLAGS != 0,
                        )
                    }.toList()
            }

        private companion object {
            /** An app of the system image, also after an update from the store. */
            const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        }
    }

/** Wiring for the trust picker. The feature keeps its own module, like `IngestModule`. */
@Module
@InstallIn(SingletonComponent::class)
abstract class KnownAppsModule {
    @Binds
    abstract fun installedAppSource(impl: PackageManagerAppSource): InstalledAppSource
}

/** The label and the icon of one package; null fields when the package is not installed. */
internal data class AppVisual(
    val label: String?,
    val icon: ImageBitmap?,
)

/**
 * Label and icon cache for the app rows. A row asks for its package when it shows, and the icon is decoded once per
 * package on the IO dispatcher. The cache is bounded, so a long list does not keep every bitmap.
 */
internal object AppVisuals {
    private const val MAX_ENTRIES = 120

    /** The bitmap size of an app icon: 40 dp at xxxhdpi (4x). */
    private const val ICON_PX = 160

    private val cache = LruCache<String, AppVisual>(MAX_ENTRIES)

    fun cached(packageName: String): AppVisual? = cache.get(packageName)

    suspend fun load(
        pm: PackageManager,
        packageName: String,
    ): AppVisual {
        cache.get(packageName)?.let { return it }
        val visual =
            withContext(Dispatchers.IO) {
                try {
                    val info = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
                    val icon = runCatching { pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }
                    AppVisual(label = pm.getApplicationLabel(info).toString(), icon = icon.getOrNull())
                } catch (_: PackageManager.NameNotFoundException) {
                    AppVisual(label = null, icon = null)
                }
            }
        cache.put(packageName, visual)
        return visual
    }
}

/** The label and icon of [packageName], read off the main thread. Previews get an empty value. */
@Composable
internal fun rememberAppVisual(packageName: String): AppVisual {
    if (LocalInspectionMode.current) return AppVisual(null, null)
    val context = LocalContext.current
    val visual by produceState(AppVisuals.cached(packageName) ?: AppVisual(null, null), packageName) {
        value = AppVisuals.load(context.packageManager, packageName)
    }
    return visual
}

/** The app icon, drawn alone. A package without an icon keeps the space, so the rows align. */
@Composable
internal fun AppIcon(
    icon: ImageBitmap?,
    modifier: Modifier = Modifier,
    size: Dp = APP_ICON_SIZE,
) {
    if (icon == null) {
        Spacer(modifier.size(size))
    } else {
        Image(bitmap = icon, contentDescription = null, modifier = modifier.size(size))
    }
}

internal val APP_ICON_SIZE = 40.dp
