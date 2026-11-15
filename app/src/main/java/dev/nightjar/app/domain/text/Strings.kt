package dev.nightjar.app.domain.text

/**
 * Access to the localised string resources. The Android adapter resolves
 * [TextKey.resource] by name and falls back to [TextKey.template] when a resource is missing, so a missing
 * translation degrades to English instead of showing a raw key.
 */
fun interface Strings {
    fun get(key: TextKey): String
}

/**
 * Class name for lists that have no message to fill from (Home "by type", Health coverage, filter chips):
 * "Push message ({contentType})" becomes "Push message". A title whose placeholder is part of the sentence ("Data
 * message to port {port}") has its own generic key ("Data message to a port").
 */
fun Strings.genericTitle(key: TextKey): String = GENERIC_KEYS[key]?.let(::get) ?: Templates.generic(get(key))

/** Titles that [Templates.generic] cannot shorten to a correct phrase. */
private val GENERIC_KEYS: Map<TextKey, TextKey> =
    mapOf(
        TextKey.TITLE_CL_DATA_PORT to TextKey.TITLE_CL_DATA_PORT_GENERIC,
        TextKey.TITLE_ST_SILENT_TYPE0_PORT to TextKey.TITLE_ST_SILENT_TYPE0_PORT_GENERIC,
    )

/** [Strings] over a plain map: used by tests and by previews. */
class MapStrings(
    private val overrides: Map<TextKey, String> = emptyMap(),
) : Strings {
    override fun get(key: TextKey): String = overrides[key] ?: key.template
}

/** `{placeholder}` substitution shared by every domain string (uses `<, >`, the resources use `{, }`). */
object Templates {
    /** Replaces every `{name}` in [template]; unknown placeholders are left as they are and reported by tests. */
    fun format(
        template: String,
        args: Map<String, String>,
    ): String {
        if (args.isEmpty() || template.indexOf('{') < 0) return template
        val out = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val open = template.indexOf('{', i)
            if (open < 0) {
                out.append(template, i, template.length)
                break
            }
            val close = template.indexOf('}', open + 1)
            if (close < 0) {
                out.append(template, i, template.length)
                break
            }
            out.append(template, i, open)
            val name = template.substring(open + 1, close)
            val value = args[name]
            if (value == null) out.append('{').append(name).append('}') else out.append(value)
            i = close + 1
        }
        return out.toString()
    }

    private val parenthetical = Regex("""\s*\([^)]*\{\w+\}[^)]*\)""")
    private val colonTail = Regex(""":\s*[^{:]*\{\w+\}\s*$""")
    private val leftover = Regex("""\s*\{\w+\}""")

    /** Drops every placeholder together with the words that only introduce it (no message to fill it from). */
    fun generic(template: String): String =
        template
            .replace(parenthetical, "")
            .replace(colonTail, "")
            .replace(leftover, "")
            .trim()

    /** Placeholder names still present in [rendered], in order of appearance (empty when fully filled). */
    fun unfilled(rendered: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            val open = rendered.indexOf('{', i)
            if (open < 0) break
            val close = rendered.indexOf('}', open + 1)
            if (close < 0) break
            out += rendered.substring(open + 1, close)
            i = close + 1
        }
        return out
    }
}
