package dev.nightjar.decoder

import dev.nightjar.pduparse.WspParser
import dev.nightjar.pduparse.WspTables
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * WSP Push PDU decoder (WAP-230).
 *
 * Header names and values are read like `WspParser` in `:pduparse` (the framework reading), so the tree and the
 * payload facts agree on the same bytes.
 */
object Wsp {
    private val HEADERS: Map<Int, String> =
        listOf(
            "Accept",
            "Accept-Charset",
            "Accept-Encoding",
            "Accept-Language",
            "Accept-Ranges",
            "Age",
            "Allow",
            "Authorization",
            "Cache-Control",
            "Connection",
            "Content-Base",
            "Content-Encoding",
            "Content-Language",
            "Content-Length",
            "Content-Location",
            "Content-MD5",
            "Content-Range",
            "Content-Type",
            "Date",
            "Etag",
            "Expires",
            "From",
            "Host",
            "If-Modified-Since",
            "If-Match",
            "If-None-Match",
            "If-Range",
            "If-Unmodified-Since",
            "Location",
            "Last-Modified",
            "Max-Forwards",
            "Pragma",
            "Proxy-Authenticate",
            "Proxy-Authorization",
            "Public",
            "Range",
            "Referer",
            "Retry-After",
            "Server",
            "Transfer-Encoding",
            "Upgrade",
            "User-Agent",
            "Vary",
            "Via",
            "Warning",
            "WWW-Authenticate",
            "Content-Disposition",
            "X-Wap-Application-Id",
            "X-Wap-Content-URI",
            "X-Wap-Initiator-URI",
            "Accept-Application",
            "Bearer-Indication",
            "Push-Flag",
            "Profile",
            "Profile-Diff",
            "Profile-Warning",
            "Expect",
            "TE",
            "Trailer",
            "Accept-Charset",
            "Accept-Encoding",
            "Cache-Control",
            "Content-Range",
            "X-Wap-Tod",
            "Content-ID",
            "Set-Cookie",
            "Cookie",
            "Encoding-Version",
            "Profile-Warning",
            "Content-Disposition",
            "X-WAP-Security",
            "Cache-Control",
        ).mapIndexed { index, name -> index to name }.toMap()

    /** Shortest length octet that quotes a following uintvar (WAP-230 8.4.2.2). */
    private const val LENGTH_QUOTE = 0x1F

    /** Longest Long-integer: Short-length is 0..30 (WAP-230 8.4.2.1). */
    private const val MAX_SHORT_LENGTH = 30

    /** Quote octet of a text string whose first character is 128 or more (WAP-230 8.4.2.1). */
    private const val TEXT_QUOTE = 0x7F

    /** Quote octet of a Quoted-string (WAP-230 8.4.2.1). */
    private const val STRING_QUOTE = 0x22

    /** A Long-integer of more than 8 octets does not fit a Long; it is shown as hex. */
    private const val MAX_LONG_OCTETS = 8

    /** OMA CP content-type parameters (OMA-WAP-ProvCont 5.3, same codes as `WspParser`): SEC and MAC. */
    private const val PARAM_SEC = 0x11
    private const val PARAM_MAC = 0x12

    /** WAP-230 well-known parameter Charset. */
    private const val PARAM_CHARSET = 0x01

    /** Registry name of a well-known content type; the table lives in the shared parser. */
    fun contentTypeName(code: Long): String = WspTables.contentTypeName(code.toInt()) ?: "unknown(0x${code.toString(16).uppercase()})"

    /** Registry name of a push application id (OMNA registry), from the shared parser. */
    fun applicationIdName(code: Long): String =
        WspTables.applicationIdName(code.toInt()) ?: "x-wap-application:unknown(0x${code.toString(16).uppercase()})"

    fun decode(bytes: ByteArray): Field = guarded("wsp_push", bytes) { decode(ByteReader(bytes)) }

    fun decode(hex: String): Field = guardedHex("wsp_push", hex) { decode(it) }

    /** A push at absolute [baseOffset], for a tree nested in a TPDU. */
    internal fun decodeAt(
        bytes: ByteArray,
        baseOffset: Int,
    ): Field = guarded("wsp_push", bytes) { decode(ByteReader(bytes, baseOffset)) }

    fun decode(reader: ByteReader): Field {
        val start = reader.absolutePos
        val children = mutableListOf<Field>()
        val tidOffset = reader.absolutePos
        // No zero-filled value for an empty input (VR01-D15).
        reader.u8OrNull()?.let { children.add(field("transaction_id", it, tidOffset, 1)) }
        val typeOffset = reader.absolutePos
        val pduType = reader.u8()
        if (reader.failed) return finish(reader, start, children)
        children.add(
            field(
                "pdu_type",
                when (pduType) {
                    0x06 -> "Push"
                    0x07 -> "Confirmed Push"
                    else -> "0x${hexByte(pduType)}"
                },
                typeOffset,
                1,
            ),
        )
        if (pduType != 0x06 && pduType != 0x07) {
            // pduparse: AN-WSP-TYPE.
            children.add(errorField(FieldNames.ERROR, typeOffset, "The PDU type is not Push (0x06) or Confirmed Push (0x07)."))
        }
        val headersLengthOffset = reader.absolutePos
        val headersLengthValue = reader.uintvar()
        if (reader.failed) return finish(reader, start, children)
        children.add(field("headers_length", headersLengthValue, headersLengthOffset, reader.absolutePos - headersLengthOffset))
        // pduparse: AN-WSP-HLEN for a length of 0 or past the data.
        if (headersLengthValue < 1) {
            reader.failAt(headersLengthOffset, "The headers length is 0. The push has no content type.")
            return finish(reader, start, children)
        }
        if (headersLengthValue > reader.remaining) {
            reader.failAt(headersLengthOffset, "The headers length is longer than the data.")
        }
        val headersLength = headersLengthValue.coerceAtMost(reader.remaining.toLong()).toInt()
        val headersOffset = reader.absolutePos
        val headers = reader.slice(headersLength)
        val contentTypeField = readContentType(headers)
        children.add(contentTypeField)
        val headerChildren = mutableListOf<Field>()
        while (headers.hasMore && !headers.failed) {
            headerChildren.add(readHeader(headers))
        }
        headers.errorField()?.let { headerChildren.add(it) }
        children.add(group("headers", headersOffset, headersLength, headerChildren))
        val bodyOffset = reader.absolutePos
        val body = reader.takeRest()
        children.add(field("body", hex(body), bodyOffset, body.size))
        if (body.isNotEmpty() && !reader.failed) nestedBody(contentTypeField.value?.toString(), body, bodyOffset)?.let { children.add(it) }
        return finish(reader, start, children)
    }

    private fun finish(
        reader: ByteReader,
        start: Int,
        children: MutableList<Field>,
    ): Field {
        reader.errorField()?.let { children.add(it) }
        return field("wsp_push", null, start, reader.absolutePos - start, children)
    }

    /** The body as a tree by its content type. The WBXML formats are in [Wbxml]. */
    private fun nestedBody(
        contentType: String?,
        body: ByteArray,
        offset: Int,
    ): Field? =
        when (contentType?.let { WspParser.normalize(it) }) {
            "application/vnd.wap.sic" -> Wbxml.decodeAt(body, offset, WbxmlKind.SI)
            "application/vnd.wap.slc" -> Wbxml.decodeAt(body, offset, WbxmlKind.SL)
            "application/vnd.wap.coc" -> Wbxml.decodeAt(body, offset, WbxmlKind.CO)
            "application/vnd.wap.connectivity-wbxml" -> OmaCp.decodeAt(body, offset)
            "application/vnd.syncml.dm+wbxml", "application/vnd.syncml+wbxml" -> Wbxml.decodeAt(body, offset, WbxmlKind.AUTO)
            "application/vnd.syncml.notification" -> OmaDm.decodeAt(body, offset)
            "application/vnd.omaloc-supl-init" -> Supl.decodeAt(body, offset)
            else -> null
        }

    /**
     * Content type (WAP-230 8.4.2.24): a short-integer, a text (Extension-media) or the general form
     * `Value-length Media-type Parameters*`. Errors follow `WspParser.contentTypeEnd` (AN-WSP-CTYPE).
     */
    private fun readContentType(reader: ByteReader): Field {
        val offset = reader.absolutePos
        if (!reader.hasMore) {
            reader.fail("The content type is missing.")
            return field("content_type", null, offset, 0, listOfNotNull(reader.errorField()))
        }
        val first = reader.peek()
        if ((first and 0x80) != 0) {
            reader.u8()
            val code = (first and 0x7F).toLong()
            return field(
                "content_type",
                contentTypeName(code),
                offset,
                1,
                listOf(field("code", code, offset, 1), field("well_known", true, offset, 1)),
            )
        }
        if (first <= LENGTH_QUOTE) return readGeneralContentType(reader, offset)
        val text = readTextString(reader)
        return field("content_type", text, offset, reader.absolutePos - offset, listOf(field("well_known", false, offset, 1)))
    }

    /** WSP general form (WAP-230 8.4.2.24): the media type is a short-integer, a Long-integer or a text. */
    private fun readGeneralContentType(
        reader: ByteReader,
        offset: Int,
    ): Field {
        val lengthOffset = reader.absolutePos
        val children = mutableListOf<Field>()
        val declared = readValueLength(reader) ?: return field("content_type", null, offset, reader.absolutePos - offset, children)
        val lengthWidth = reader.absolutePos - lengthOffset
        children.add(field("value_length", declared, lengthOffset, lengthWidth))
        if (declared < 1) {
            reader.failAt(lengthOffset, "The content type value length is 0.")
            return field("content_type", null, offset, reader.absolutePos - offset, children)
        }
        val region = reader.slice(declared)
        // The value length runs past the end and no media type octet is there: no well_known node (VR01-D15).
        if (!region.hasMore) return field("content_type", null, offset, reader.absolutePos - offset, children)
        val mediaOffset = region.absolutePos
        val first = region.peek()
        var code: Long? = null
        var text: String? = null
        when {
            (first and 0x80) != 0 -> {
                code = (region.u8() and 0x7F).toLong()
            }

            first == 0 || first == LENGTH_QUOTE -> {
                region.failAt(mediaOffset, "The media type is not valid.")
            }

            first <= MAX_SHORT_LENGTH -> {
                val value = readLongInteger(region)
                code = value.number
                if (code == null) text = value.text
            }

            else -> {
                text = readTextString(region)
            }
        }
        val mediaLength = region.absolutePos - mediaOffset
        children.add(field("well_known", code != null, mediaOffset, mediaLength))
        if (code != null) children.add(field("code", code, mediaOffset, mediaLength))
        if (!region.failed && region.hasMore) {
            val parametersOffset = region.absolutePos
            val parameters = mutableListOf<Field>()
            while (region.hasMore && !region.failed) parameters.add(readParameter(region))
            children.add(group("parameters", parametersOffset, region.absolutePos - parametersOffset, parameters))
        }
        return field(
            "content_type",
            if (code != null) contentTypeName(code) else text,
            offset,
            reader.absolutePos - offset,
            children,
        )
    }

    /**
     * One content-type parameter (WAP-230 8.4.2.4): a typed parameter (well-known code) or an untyped parameter (text
     * name). SEC and MAC are named, like `WspParser.parameters`.
     */
    private fun readParameter(reader: ByteReader): Field {
        val offset = reader.absolutePos
        val first = reader.peek()
        val code: Long?
        val name: String
        when {
            (first and 0x80) != 0 -> {
                code = (reader.u8() and 0x7F).toLong()
                name = parameterName(code)
            }

            first <= MAX_SHORT_LENGTH -> {
                val value = readLongInteger(reader)
                code = value.number
                name = code?.let { parameterName(it) } ?: "param-${value.text}"
            }

            else -> {
                code = null
                name = readTextString(reader)
            }
        }
        val valueOffset = reader.absolutePos
        val children = mutableListOf(field("name", name, offset, valueOffset - offset))
        if (reader.failed || !reader.hasMore) return field("parameter", null, offset, reader.absolutePos - offset, children)
        val value: Any?
        if (code == PARAM_SEC.toLong() && (reader.peek() and 0x80) != 0) {
            val sec = reader.u8() and 0x7F
            value = sec
            children.add(field("meaning", secMeaning(sec), valueOffset, 1))
        } else {
            value = readValue(reader).first
        }
        children.add(field("value", value, valueOffset, reader.absolutePos - valueOffset))
        return field("parameter", value, offset, reader.absolutePos - offset, children)
    }

    private fun parameterName(code: Long): String =
        when (code.toInt()) {
            PARAM_CHARSET -> "Charset"
            PARAM_SEC -> "SEC"
            PARAM_MAC -> "MAC"
            else -> "param-0x${hexByte(code.toInt())}"
        }

    /** OMA-WAP-ProvCont 5.3: the SEC values. */
    private fun secMeaning(sec: Int): String =
        when (sec) {
            0 -> "NETWPIN"
            1 -> "USERPIN"
            2 -> "USERNETWPIN"
            3 -> "USERPINMAC"
            else -> "unknown"
        }

    /**
     * Reads a WSP Value-length: one octet up to 30, or 0x1F followed by a uintvar. Null when the length runs past
     * the end of the data (VR01-D15).
     */
    private fun readValueLength(reader: ByteReader): Int? {
        val first = reader.u8OrNull() ?: return null
        if (first != LENGTH_QUOTE) return first
        val value = reader.uintvarOrNull() ?: return null
        val tooLarge = value > Int.MAX_VALUE
        if (tooLarge) reader.fail("The value length is too large.")
        return if (tooLarge) 0 else value.toInt()
    }

    /**
     * A header. The name is a short-integer (well-known), a Long-integer for 0x00-0x1E (the framework reading that
     * `WspParser.nameEnd` uses; WAP-230 8.4.2.6 calls 0x01-0x1F a short-cut shift) or a text.
     */
    private fun readHeader(reader: ByteReader): Field {
        val offset = reader.absolutePos
        val first = reader.peek()
        val name: String
        val nameChildren = mutableListOf<Field>()
        when {
            (first and 0x80) != 0 -> {
                val code = reader.u8() and 0x7F
                name = HEADERS[code] ?: "header-0x${hexByte(code)}"
            }

            first <= MAX_SHORT_LENGTH -> {
                val value = readLongInteger(reader)
                val code = value.number
                name = code?.let { HEADERS[it.toInt()] ?: "header-0x${it.toString(16).uppercase()}" } ?: "header-${value.text}"
                nameChildren.add(
                    field(
                        "note",
                        "The name is read as a Long-integer like the Android framework. WAP-230 reads 0x01-0x1F as a code page shift.",
                        offset,
                        0,
                    ),
                )
            }

            else -> {
                name = readTextString(reader)
            }
        }
        val nameLength = reader.absolutePos - offset
        val children = mutableListOf<Field>()
        children.add(field("name", name, offset, nameLength, nameChildren))
        if (reader.failed) return field("header", null, offset, nameLength, children)
        val valueOffset = reader.absolutePos
        val (value, kind) = readHeaderValue(reader, name)
        val valueLength = reader.absolutePos - valueOffset
        children.add(field("value", value, valueOffset, valueLength))
        children.add(field("value_kind", kind, valueOffset, valueLength))
        return field("header", value, offset, nameLength + valueLength, children)
    }

    private fun readHeaderValue(
        reader: ByteReader,
        name: String,
    ): Pair<String, String> {
        if (!reader.hasMore) return "" to "empty"
        return when (name) {
            "Content-Type" -> {
                val field = readContentType(reader)
                (field.value?.toString() ?: "") to "content-type"
            }

            "X-Wap-Application-Id" -> {
                val peek = reader.peek()
                when {
                    (peek and 0x80) != 0 -> {
                        applicationIdName((reader.u8() and 0x7F).toLong()) to "integer"
                    }

                    peek == 0 || peek == LENGTH_QUOTE -> {
                        // pduparse: AN-WSP-APPID.
                        reader.fail("The application id is not an Integer-value or a text.")
                        "" to "invalid"
                    }

                    peek <= MAX_SHORT_LENGTH -> {
                        val value = readLongInteger(reader)
                        (value.number?.let { applicationIdName(it) } ?: value.text) to "integer"
                    }

                    else -> {
                        readTextString(reader) to "text"
                    }
                }
            }

            else -> {
                val (value, kind) = readValue(reader)
                (value?.toString() ?: "") to kind
            }
        }
    }

    /**
     * A field value by its first octet (WAP-230 8.4.1.2, the `WspParser.skipValue` rule): 0x00-0x1E Short-length
     * data, 0x1F a uintvar length, 0x20-0x7F a text, 0x80-0xFF a short-integer. Data in the general form is shown
     * as hex.
     */
    private fun readValue(reader: ByteReader): Pair<Any?, String> {
        val first = reader.peek()
        return when {
            (first and 0x80) != 0 -> {
                (reader.u8() and 0x7F) to "integer"
            }

            first <= MAX_SHORT_LENGTH -> {
                reader.u8()
                hex(reader.take(first)) to "general"
            }

            first == LENGTH_QUOTE -> {
                reader.u8()
                val length = reader.uintvar()
                if (length > reader.remaining) {
                    reader.fail("The value length is longer than the headers.")
                    reader.takeRest()
                    "" to "general"
                } else {
                    hex(reader.take(length.toInt())) to "general"
                }
            }

            else -> {
                readTextString(reader) to "text"
            }
        }
    }

    private class LongInteger(
        val number: Long?,
        val text: String,
    )

    /**
     * A Long-integer (WAP-230 8.4.2.1): Short-length 0..30, then the octets. A value of more than 8 octets does not
     * fit a Long and is given as hex text only.
     */
    private fun readLongInteger(reader: ByteReader): LongInteger {
        val length = reader.u8()
        if (length > MAX_SHORT_LENGTH) {
            reader.fail("The Long-integer length is more than 30.")
            return LongInteger(null, "")
        }
        val bytes = reader.take(length)
        // A Long-integer cut by the end of the data has no number, only the octets that are there (VR01-D15).
        if (bytes.size > MAX_LONG_OCTETS || bytes.size < length) return LongInteger(null, "0x" + hex(bytes))
        var value = 0L
        for (b in bytes) value = (value shl 8) or (b.toInt() and 0xFF).toLong()
        return LongInteger(value, "0x" + hex(bytes))
    }

    /**
     * A NUL-terminated text. A leading Quote (0x7F) or the 0x22 of a Quoted-string is not part of the text. The
     * octets are UTF-8 like `WspParser` and the Android `WspTypeDecoder`; invalid UTF-8 is read as ISO-8859-1
     * .
     */
    private fun readTextString(reader: ByteReader): String {
        if (!reader.hasMore) return ""
        val lead = reader.peek()
        if (lead == TEXT_QUOTE || lead == STRING_QUOTE) reader.u8()
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
        if (!terminated) reader.failAt(start, "The text string has no NUL terminator.")
        return text(bytes.toByteArray())
    }

    /** UTF-8 text, or ISO 8859-1 when the octets are not valid UTF-8. The decoder reports errors as a result, not an exception. */
    internal fun text(bytes: ByteArray): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        // UTF-8 gives at most one char for each octet, so the output buffer cannot overflow.
        val out = CharBuffer.allocate(bytes.size)
        val decoded = decoder.decode(ByteBuffer.wrap(bytes), out, true)
        val result = if (decoded.isError) decoded else decoder.flush(out)
        if (result.isError) return String(bytes, StandardCharsets.ISO_8859_1)
        out.flip()
        return out.toString()
    }
}
