package dev.nightjar.decoder

import java.util.Locale

/**
 * Canonical JSON for a [Field] tree.
 *
 * The contract is deliberately small and deterministic:
 * - compact UTF-8 text, no insignificant whitespace;
 * - keys in the order `name`, `value`, `offset`, `length`, `error`, `children`;
 * - `value` is omitted when null, `error` when null, `children` when empty;
 * - strings escape quote and backslash; newline, carriage return, tab, backspace and form feed use the short
 *   escapes `\n \r \t \b \f`; every other control code unit and every code unit above 0x7E is written as
 *   `\uxxxx` with lower-case hex digits (the goldens depend on this form);
 * - numbers and booleans are unquoted.
 *
 * The same writer is used for fixtures, so a golden fixture is an exact byte-for-byte expectation.
 */
object FieldJson {
    fun toJson(field: Field): String =
        buildString(estimatedSize(field)) {
            appendField(this, field)
        }

    private fun estimatedSize(field: Field): Int = 48 + (field.value?.toString()?.length ?: 0) + field.children.size * 32

    private fun appendField(out: StringBuilder, field: Field) {
        out.append('{')
        var first = true

        fun key(name: String) {
            if (!first) out.append(',')
            first = false
            appendString(out, name)
            out.append(':')
        }
        key(FieldNames.NAME)
        appendString(out, field.name)
        if (field.value != null) {
            key(FieldNames.VALUE)
            appendValue(out, field.value)
        }
        key(FieldNames.OFFSET)
        out.append(field.offset)
        key(FieldNames.LENGTH)
        out.append(field.length)
        if (field.error != null) {
            key(FieldNames.ERROR)
            appendString(out, field.error)
        }
        if (field.children.isNotEmpty()) {
            key(FieldNames.CHILDREN)
            out.append('[')
            field.children.forEachIndexed { index, child ->
                if (index > 0) out.append(',')
                appendField(out, child)
            }
            out.append(']')
        }
        out.append('}')
    }

    private fun appendValue(out: StringBuilder, value: Any) {
        when (value) {
            is String -> appendString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value)
            is Double -> if (value.isFinite()) out.append(value) else appendString(out, value.toString())
            else -> appendString(out, value.toString())
        }
    }

    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '\b' -> out.append("\\b")
                ch == '\u000C' -> out.append("\\f")
                ch.code < 0x20 || ch.code > 0x7E -> out.append("\\u").append(hexDigits(ch.code.toLong(), 4).lowercase(Locale.ROOT))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }
}
