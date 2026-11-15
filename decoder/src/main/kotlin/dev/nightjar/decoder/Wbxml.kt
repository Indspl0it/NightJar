package dev.nightjar.decoder

import java.nio.charset.StandardCharsets

/** Result of a WBXML decode: the byte-ranged field tree plus the reconstructed XML text. */
data class WbxmlResult(
    val field: Field,
    val xml: String,
)

/**
 * WBXML 1.0-1.3 parser for the push code pages (WAP-192).
 *
 * The tag code page and the attribute code page are two separate states (WAP-192 5.8.1). Global tokens are
 * dispatched first; in tag space every other token is a tag, in attribute space every other token below 0x80 is an
 * attribute start and every other token from 0x80 is an attribute value (WAP-192 5.8.2, 5.8.3).
 */
object Wbxml {
    /** Maximum element nesting; deeper documents fail instead of exhausting the stack. */
    private const val MAX_DEPTH = 64

    /** Maximum reconstructed XML size; a push cannot amplify beyond this. */
    private const val MAX_XML = 256 * 1024

    /** `#text` nodes keep only a preview so a string table cannot amplify the tree. */
    private const val MAX_TEXT_PREVIEW = 256

    /** Highest WBXML version this parser knows: 1.3 (pduparse flags AN-WBXML-VER above it). */
    private const val MAX_VERSION = 0x03

    // WAP-192 5.8.4 global tokens.
    private const val SWITCH_PAGE = 0x00
    private const val END = 0x01
    private const val ENTITY = 0x02
    private const val STR_I = 0x03
    private const val LITERAL = 0x04
    private const val EXT_I_0 = 0x40
    private const val EXT_I_2 = 0x42
    private const val PI = 0x43
    private const val LITERAL_C = 0x44
    private const val EXT_T_0 = 0x80
    private const val EXT_T_2 = 0x82
    private const val STR_T = 0x83
    private const val EXT_0 = 0xC0
    private const val EXT_2 = 0xC2
    private const val OPAQUE = 0xC3

    fun decode(
        bytes: ByteArray,
        kind: WbxmlKind = WbxmlKind.AUTO,
    ): WbxmlResult = decodeReader(bytes, ByteReader(bytes), kind)

    fun decode(
        hex: String,
        kind: WbxmlKind = WbxmlKind.AUTO,
    ): WbxmlResult {
        val bytes =
            Hex.decodeOrNull(hex) ?: return WbxmlResult(Field("wbxml", offset = 0, length = 0, error = "The input is not valid hex."), "")
        return decode(bytes, kind)
    }

    /** A document at absolute [baseOffset], for a tree nested in a push. */
    internal fun decodeAt(
        bytes: ByteArray,
        baseOffset: Int,
        kind: WbxmlKind,
    ): Field = decodeReader(bytes, ByteReader(bytes, baseOffset), kind).field

    private fun decodeReader(
        bytes: ByteArray,
        reader: ByteReader,
        kind: WbxmlKind,
    ): WbxmlResult =
        try {
            Parser(reader, kind).parse()
        } catch (t: Throwable) {
            WbxmlResult(
                Field(
                    "wbxml",
                    offset = reader.baseOffset,
                    length = bytes.size,
                    error = "The decoder stopped because of an internal error (${t.javaClass.simpleName}).",
                ),
                "",
            )
        }

    private class Parser(
        private val reader: ByteReader,
        hint: WbxmlKind,
    ) {
        private val xml = StringBuilder()
        private var table = ByteArray(0)
        private var charset = 0x6A
        private var kind = hint
        private var truncated = false

        /** Tag code page and attribute code page: two states (WAP-192 5.8.1). */
        private var tagPage = 0
        private var attrPage = 0

        fun parse(): WbxmlResult {
            val start = reader.absolutePos
            val children = mutableListOf<Field>()
            parseHeader(children)
            val documentOffset = reader.absolutePos
            val documentChildren = mutableListOf<Field>()
            // body = *pi element *pi (WAP-192 5.3).
            while (reader.hasMore && !reader.failed) {
                val offset = reader.absolutePos
                when (reader.peek()) {
                    SWITCH_PAGE -> {
                        reader.u8()
                        tagPage = reader.u8()
                    }

                    PI -> {
                        reader.u8()
                        documentChildren.add(processingInstruction(offset))
                    }

                    END -> {
                        // pduparse: AN-WBXML-TOK.
                        reader.failAt(offset, "The END token has no open element.")
                    }

                    else -> {
                        documentChildren.add(parseElement(1))
                    }
                }
            }
            reader.errorField()?.let { documentChildren.add(it) }
            children.add(group("document", documentOffset, reader.absolutePos - documentOffset, documentChildren))
            children.add(field("xml", xml.toString(), documentOffset, reader.absolutePos - documentOffset))
            return WbxmlResult(field("wbxml", null, start, reader.absolutePos - start, children), xml.toString())
        }

        /** Version, public id, charset and string table (WAP-192 5.3-5.7). A field past the end gets no node. */
        private fun parseHeader(children: MutableList<Field>) {
            val versionOffset = reader.absolutePos
            // A header field past the end of the data gets no node, only the error node (VR01-D15).
            val version = reader.u8OrNull()
            if (version != null) children.add(field("version", hexByte(version), versionOffset, 1))
            if (version != null && version > MAX_VERSION) {
                children.add(errorField(FieldNames.ERROR, versionOffset, "The WBXML version 0x${hexByte(version)} is not known."))
            }
            val publicIdSlot = children.size
            val publicIdOffset = reader.absolutePos
            val publicIdValue = reader.uintvarOrNull()
            val publicId = publicIdValue ?: 0L
            val publicIdLength = reader.absolutePos - publicIdOffset
            // WAP-192 5.5: public id 0 means that a string table index follows.
            var publicIdIndex: Long? = null
            var indexOffset = 0
            var indexLength = 0
            if (!reader.failed && publicId == 0L) {
                indexOffset = reader.absolutePos
                publicIdIndex = reader.uintvarOrNull()
                indexLength = reader.absolutePos - indexOffset
            }
            if (kind == WbxmlKind.AUTO) kind = WbxmlCodePages.kindForPublicId(publicId)
            // WBXML 1.0 has no charset field (WAP-192 5.4).
            if (version != 0x00 && !reader.failed) {
                val charsetOffset = reader.absolutePos
                reader.uintvarOrNull()?.let {
                    charset = it.toInt()
                    children.add(field("charset", charset, charsetOffset, reader.absolutePos - charsetOffset))
                }
            }
            val tableOffset = reader.absolutePos
            val tableLength = reader.uintvarOrNull()
            if (tableLength != null && !reader.failed) {
                if (tableLength > reader.remaining) {
                    reader.fail("The string table is longer than the data.")
                } else {
                    table = reader.take(tableLength.toInt())
                }
            }
            val publicIdChildren = mutableListOf<Field>()
            if (publicIdIndex != null) {
                val name = tableString(publicIdIndex)
                publicIdChildren.add(field("string_table_index", publicIdIndex, indexOffset, indexLength))
                if (name == null) {
                    publicIdChildren.add(
                        errorField(FieldNames.ERROR, indexOffset, "The string table index $publicIdIndex is outside the table."),
                    )
                } else {
                    publicIdChildren.add(field("name", name, indexOffset, indexLength))
                }
            } else {
                publicIdChildren.add(field("name", WbxmlCodePages.publicIdName(publicId), publicIdOffset, publicIdLength))
            }
            if (publicIdValue != null) {
                children.add(publicIdSlot, field("public_id", publicId, publicIdOffset, publicIdLength + indexLength, publicIdChildren))
            }
            if (tableLength != null) {
                children.add(
                    field(
                        "string_table",
                        hex(table),
                        tableOffset,
                        reader.absolutePos - tableOffset,
                        listOf(field("length", tableLength, tableOffset, reader.absolutePos - tableOffset)),
                    ),
                )
            }
        }

        /** The string-table text at [offset] up to the next NUL (WAP-192 5.7), or null outside the table. */
        private fun tableString(offset: Long): String? {
            if (offset < 0 || offset >= table.size) return null
            val from = offset.toInt()
            var end = from
            while (end < table.size && table[end].toInt() != 0) end++
            return String(table, from, end - from, charset())
        }

        /** A string-table reference that must be inside the table (pduparse: AN-WBXML-STR). */
        private fun tableReference(offset: Int): String {
            val index = reader.uintvar()
            if (reader.failed) return ""
            return tableString(index) ?: run {
                reader.failAt(offset, "The string table reference $index is outside the table.")
                ""
            }
        }

        private fun parseElement(depth: Int): Field {
            val tokenOffset = reader.absolutePos
            if (depth > MAX_DEPTH) {
                reader.failAt(tokenOffset, "The elements are nested more than $MAX_DEPTH levels deep.")
                return field("element", null, tokenOffset, 0)
            }
            val token = reader.u8()
            val tagToken = token and 0x3F
            val hasAttributes = (token and 0x80) != 0
            val hasContent = (token and 0x40) != 0
            val name =
                if (tagToken == LITERAL) {
                    val literalOffset = reader.absolutePos
                    tableReference(literalOffset).ifEmpty { "literal" }
                } else {
                    WbxmlCodePages.tag(kind, tagPage, tagToken) ?: "token_0x${hexByte(tagToken)}"
                }
            val children = mutableListOf<Field>()
            appendXml("<")
            appendXml(name)
            if (hasAttributes && !reader.failed) parseAttributes(children)
            if (hasContent && !reader.failed) {
                appendXml(">")
                parseContent(name, children, depth)
                appendXml("</")
                appendXml(name)
                appendXml(">")
            } else {
                appendXml("/>")
            }
            return field(name, null, tokenOffset, reader.absolutePos - tokenOffset, children)
        }

        /** An attribute list up to its END (WAP-192 5.8.3). Only LITERAL (0x04) is a literal here. */
        private fun parseAttributes(children: MutableList<Field>) {
            while (!reader.failed) {
                if (!reader.hasMore) {
                    reader.fail("The attribute list has no END token.")
                    return
                }
                val attrOffset = reader.absolutePos
                val token = reader.peek()
                when {
                    token == END -> {
                        reader.u8()
                        return
                    }

                    token == SWITCH_PAGE -> {
                        reader.u8()
                        attrPage = reader.u8()
                    }

                    isAttributeStart(token) -> {
                        val (attrName, value) = parseAttribute()
                        appendXml(" ")
                        appendXml(attrName)
                        appendXml("=\"")
                        appendXml(xmlEscape(value))
                        appendXml("\"")
                        children.add(field("@$attrName", value, attrOffset, reader.absolutePos - attrOffset))
                    }

                    else -> {
                        reader.failAt(attrOffset, "The token 0x${hexByte(token)} is not valid in an attribute list.")
                        return
                    }
                }
            }
        }

        /** True for LITERAL and the attribute-start tokens 0x05-0x3F and 0x45-0x7F. */
        private fun isAttributeStart(token: Int): Boolean = token == LITERAL || (token in 0x05..0x7F && token !in EXT_I_0..LITERAL_C)

        /** True for the attribute-value tokens: strings, extensions, entity, opaque and 0x85-0xBF, 0xC5-0xFF. */
        private fun isAttributeValue(token: Int): Boolean =
            token == ENTITY || token == STR_I || token in EXT_I_0..EXT_I_2 || token in EXT_T_0..STR_T ||
                token in EXT_0..OPAQUE || token in 0x85..0xBF || token >= 0xC5

        /**
         * One attribute: ATTRSTART (or LITERAL + table index), then every attribute value that follows. The value is
         * the prefix of the attribute start plus the values (WAP-192 5.8.3).
         */
        private fun parseAttribute(): Pair<String, String> {
            val startOffset = reader.absolutePos
            val token = reader.u8()
            val attrName: String
            val value = StringBuilder()
            if (token == LITERAL) {
                attrName = tableReference(startOffset).ifEmpty { "literal" }
            } else {
                val spec = WbxmlCodePages.attribute(kind, attrPage, token)
                attrName = spec?.name ?: "attr_0x${hexByte(token)}"
                spec?.implied?.let { value.append(it) }
            }
            while (reader.hasMore && !reader.failed) {
                val next = reader.peek()
                if (next == SWITCH_PAGE) {
                    reader.u8()
                    attrPage = reader.u8()
                    continue
                }
                if (!isAttributeValue(next)) break
                value.append(readAttributeValue(attrName))
            }
            return attrName to value.toString()
        }

        private fun readAttributeValue(attrName: String): String {
            val offset = reader.absolutePos
            return when (val token = reader.u8()) {
                STR_I -> {
                    readInlineString()
                }

                STR_T -> {
                    tableReference(offset)
                }

                ENTITY -> {
                    reader.uintvarOrNull()?.let { "&#x" + it.toString(16).uppercase() + ";" } ?: ""
                }

                OPAQUE -> {
                    val data = readOpaque()
                    if (attrName == "created" || attrName == "si-expires") decodeSiDatetime(data) else hex(data)
                }

                in EXT_I_0..EXT_I_2 -> {
                    readInlineString()
                }

                in EXT_T_0..EXT_T_2 -> {
                    reader.uintvarOrNull()?.toString() ?: ""
                }

                in EXT_0..EXT_2 -> {
                    ""
                }

                else -> {
                    WbxmlCodePages.attributeValue(kind, attrPage, token) ?: hexByte(token)
                }
            }
        }

        private fun readOpaque(): ByteArray {
            val length = reader.uintvar()
            if (reader.failed) return ByteArray(0)
            if (length > reader.remaining) {
                reader.fail("The opaque data is longer than the document.")
                return reader.takeRest()
            }
            return reader.take(length.toInt())
        }

        /** PI = attrStart *attrValue END (WAP-192 5.8.5). */
        private fun processingInstruction(offset: Int): Field {
            if (!reader.hasMore || !isAttributeStart(reader.peek())) {
                reader.failAt(offset, "The processing instruction has no target.")
                return field("#pi", null, offset, reader.absolutePos - offset)
            }
            val (target, value) = parseAttribute()
            if (!reader.failed) {
                if (reader.hasMore && reader.peek() == END) {
                    reader.u8()
                } else {
                    reader.failAt(reader.absolutePos, "The processing instruction has no END token.")
                }
            }
            appendXml("<?$target ${xmlEscape(value)}?>")
            return field("#pi", "$target $value".trim(), offset, reader.absolutePos - offset)
        }

        /** Element content (WAP-192 5.8.2): global tokens first, every other token is a tag. */
        private fun parseContent(
            elementName: String,
            children: MutableList<Field>,
            depth: Int,
        ) {
            while (reader.hasMore && !reader.failed) {
                val offset = reader.absolutePos
                when (val token = reader.peek()) {
                    END -> {
                        reader.u8()
                        return
                    }

                    SWITCH_PAGE -> {
                        reader.u8()
                        tagPage = reader.u8()
                    }

                    STR_I -> {
                        reader.u8()
                        text(readInlineString(), offset, children)
                    }

                    STR_T -> {
                        reader.u8()
                        text(tableReference(offset), offset, children)
                    }

                    ENTITY -> {
                        reader.u8()
                        // WAP-192 5.8.4.3: the entity code is an mb_u_int32.
                        reader.uintvarOrNull()?.let { entity ->
                            appendXml("&#x" + entity.toString(16).uppercase() + ";")
                            children.add(field("#entity", entity, offset, reader.absolutePos - offset))
                        }
                    }

                    OPAQUE -> {
                        reader.u8()
                        val hexData = hex(readOpaque())
                        appendXml(hexData)
                        children.add(field("opaque", hexData, offset, reader.absolutePos - offset))
                    }

                    PI -> {
                        reader.u8()
                        children.add(processingInstruction(offset))
                    }

                    in EXT_I_0..EXT_I_2 -> {
                        reader.u8()
                        children.add(field("#ext_i_${token - EXT_I_0}", preview(readInlineString()), offset, reader.absolutePos - offset))
                    }

                    in EXT_T_0..EXT_T_2 -> {
                        reader.u8()
                        reader.uintvarOrNull()?.let {
                            children.add(field("#ext_t_${token - EXT_T_0}", it, offset, reader.absolutePos - offset))
                        }
                    }

                    in EXT_0..EXT_2 -> {
                        reader.u8()
                        children.add(field("#ext_${token - EXT_0}", null, offset, 1))
                    }

                    else -> {
                        children.add(parseElement(depth + 1))
                    }
                }
            }
            if (!reader.failed) reader.fail("The <$elementName> element has no END token.")
        }

        private fun text(
            text: String,
            offset: Int,
            children: MutableList<Field>,
        ) {
            appendXml(xmlEscape(text))
            children.add(field("#text", preview(text), offset, reader.absolutePos - offset))
        }

        private fun readInlineString(): String {
            val start = reader.absolutePos
            val bytes = java.io.ByteArrayOutputStream()
            var terminated = false
            while (reader.hasMore) {
                val b = reader.u8()
                if (b == 0) {
                    terminated = true
                    break
                }
                bytes.write(b)
            }
            if (!terminated) reader.failAt(start, "The inline string has no NUL terminator.")
            return String(bytes.toByteArray(), charset())
        }

        private fun charset(): java.nio.charset.Charset =
            when (charset) {
                0x03 -> StandardCharsets.US_ASCII
                0x04 -> StandardCharsets.ISO_8859_1
                0x6A -> StandardCharsets.UTF_8
                0x03E8 -> StandardCharsets.UTF_16BE
                else -> StandardCharsets.ISO_8859_1
            }

        /** SI date (WAP-167 8.2.2): BCD digits YYYYMMDDhhmmss in OPAQUE data, trailing zero octets left out. */
        private fun decodeSiDatetime(bytes: ByteArray): String {
            val digits = StringBuilder(bytes.size * 2)
            for (b in bytes) digits.append(hexByte(b.toInt()))
            while (digits.length < 14) digits.append('0')
            if (digits.length > 14) digits.setLength(14)
            val text = digits.toString()
            val year = text.substring(0, 4)
            val month = text.substring(4, 6)
            val day = text.substring(6, 8)
            val hour = text.substring(8, 10)
            val minute = text.substring(10, 12)
            val second = text.substring(12, 14)
            return "$year-$month-${day}T$hour:$minute:${second}Z"
        }

        /**
         * Appends to the reconstructed XML, refusing to grow past [MAX_XML]. Once the budget is spent the decode
         * stops and an error node is recorded, so the result stays bounded.
         */
        private fun appendXml(text: String) {
            if (truncated) return
            if (xml.length + text.length > MAX_XML) {
                truncated = true
                reader.fail("The XML text is longer than the output limit.")
                return
            }
            xml.append(text)
        }

        private fun preview(text: String): String = if (text.length <= MAX_TEXT_PREVIEW) text else text.substring(0, MAX_TEXT_PREVIEW)

        private fun xmlEscape(text: String): String =
            buildString(text.length) {
                for (ch in text) {
                    when (ch) {
                        '&' -> append("&amp;")
                        '<' -> append("&lt;")
                        '>' -> append("&gt;")
                        '"' -> append("&quot;")
                        '\'' -> append("&apos;")
                        else -> append(ch)
                    }
                }
            }
    }
}
