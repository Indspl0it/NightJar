package dev.nightjar.app.domain.known

/** One installed app as the trust picker shows it. [system] is true for an app of the system image. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
)

/** The picker filter chips. */
enum class AppFilter {
    USER,
    SYSTEM,
    ALL,
}

/** What a picker save writes: the packages to trust and the packages to stop trusting. */
data class TrustChanges(
    val add: Set<String>,
    val remove: Set<String>,
) {
    val count: Int get() = add.size + remove.size

    val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()
}

/**
 * The pure logic of the trust picker: sort, filter, search and the selection operations.
 *
 * Select all, Invert and Clear act on the rows on the screen (the current filter and search), so a hidden row never
 * changes without the user seeing it. A trusted app that is not installed any more is not in the list: the picker keeps
 * it as it is, and the Apps tab can remove it.
 */
object AppPicker {
    /** By label (case folded), then by package name, so two apps with the same label keep a fixed order. */
    fun sorted(apps: List<InstalledApp>): List<InstalledApp> =
        apps.sortedWith(compareBy<InstalledApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.packageName })

    /** The rows to show for [filter] and [query]. The query matches the label or the package name, case folded. */
    fun visible(
        apps: List<InstalledApp>,
        query: String,
        filter: AppFilter,
    ): List<InstalledApp> {
        val needle = query.trim()
        return apps.filter { app -> app.passes(filter) && app.matches(needle) }
    }

    private fun InstalledApp.passes(filter: AppFilter): Boolean =
        when (filter) {
            AppFilter.USER -> !system
            AppFilter.SYSTEM -> system
            AppFilter.ALL -> true
        }

    private fun InstalledApp.matches(needle: String): Boolean =
        needle.isEmpty() || label.contains(needle, ignoreCase = true) || packageName.contains(needle, ignoreCase = true)

    fun toggle(
        selected: Set<String>,
        packageName: String,
    ): Set<String> = if (packageName in selected) selected - packageName else selected + packageName

    fun selectAll(
        selected: Set<String>,
        visible: List<InstalledApp>,
    ): Set<String> = selected + visible.map { it.packageName }

    fun invert(
        selected: Set<String>,
        visible: List<InstalledApp>,
    ): Set<String> {
        val shown = visible.mapTo(HashSet()) { it.packageName }
        return (selected - shown) + (shown - selected)
    }

    fun clear(
        selected: Set<String>,
        visible: List<InstalledApp>,
    ): Set<String> = selected - visible.mapTo(HashSet()) { it.packageName }

    /** The writes that turn [trusted] into [selected]. */
    fun changes(
        trusted: Set<String>,
        selected: Set<String>,
    ): TrustChanges = TrustChanges(add = selected - trusted, remove = trusted - selected)
}
