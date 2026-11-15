package dev.nightjar.app.ui.text

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import dev.nightjar.app.domain.text.MapStrings
import dev.nightjar.app.domain.text.Strings
import dev.nightjar.app.domain.text.TextKey
import java.util.concurrent.ConcurrentHashMap

/**
 * [Strings] over the app's resources.
 *
 * [TextKey.resource] is resolved by name, so a localised resource wins as soon as it exists and a missing one degrades
 * to the English template instead of showing a raw key. Ids are cached: the first lookup of a key costs one
 * `getIdentifier`, every later one is a map hit. The cache is safe for more than one thread: the UI and the background
 * ingest and notification code use the same instance.
 */
class ResourceStrings(
    private val resources: Resources,
    private val packageName: String,
) : Strings {
    private val cache = ConcurrentHashMap<TextKey, Int>(TextKey.entries.size)

    @SuppressLint("DiscouragedApi") // resolving the app's own resources by their fixed names; ids are cached.
    override fun get(key: TextKey): String {
        val id = cache.getOrPut(key) { resources.getIdentifier(key.resource, "string", packageName) }
        if (id == 0) return key.template
        return runCatching { resources.getString(id) }.getOrDefault(key.template)
    }

    companion object {
        fun from(context: Context): ResourceStrings =
            ResourceStrings(context.resources, context.packageName)
    }
}

/**
 * The strings of the current context.
 *
 * There is deliberately no English default: a build that forgot the provider would silently ignore
 * `res/values-<qualifier>/strings.xml` — exactly the defect this local exists to fix — so reading it without a provider is a
 * programming error. Previews provide [MapStrings] through `PreviewTheme`, and tests provide it with
 * `CompositionLocalProvider(LocalStrings provides MapStrings())`.
 */
val LocalStrings =
    staticCompositionLocalOf<Strings> {
        error("LocalStrings not provided: wrap the content in CompositionLocalProvider(LocalStrings provides strings)")
    }

/** Remembers a [ResourceStrings] for the current context. */
@Composable
fun rememberStrings(): Strings {
    val context = LocalContext.current
    return remember(context) { ResourceStrings.from(context) }
}
