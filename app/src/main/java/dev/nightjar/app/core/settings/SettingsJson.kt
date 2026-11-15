package dev.nightjar.app.core.settings

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The severity-override map in its stored form (`severity.<classId>`).
 *
 * `settings.json` itself is built by `ModuleSettingsFactory`: it needs the package name and the signing
 * certificate, which are not available to a pure object.
 */
object SettingsJson {
    /** `severity.<classId>` overrides as a JSON object keyed by wire name; `{}` when there are none. */
    fun writeSeverityOverrides(overrides: Map<ClassId, Severity>): String =
        buildJsonObject {
            overrides.forEach { (classId, severity) -> put(classId.wire(), severity.wire()) }
        }.toString()

    fun readSeverityOverrides(text: String?): Map<ClassId, Severity> {
        if (text.isNullOrBlank()) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<ClassId, Severity>(root.size)
        root.entries.forEach { (key, value) ->
            val classId = ClassId.entries.firstOrNull { it.wire() == key } ?: return@forEach
            val wire = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return@forEach
            val severity = Severity.entries.firstOrNull { it.wire() == wire } ?: return@forEach
            out[classId] = severity
        }
        return out
    }
}
