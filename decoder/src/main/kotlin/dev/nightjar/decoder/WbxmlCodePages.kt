package dev.nightjar.decoder

/** WBXML document family selected by public identifier or by the caller. */
enum class WbxmlKind {
    AUTO,
    SI,
    SL,
    CO,
    OMA_CP,
}

/**
 * Attribute start token: a name plus the value prefix that the token implies (WAP-192 5.8.3). Attribute values that
 * follow are appended to the prefix.
 */
internal data class AttrSpec(
    val name: String,
    val implied: String? = null,
)

/** WBXML public identifiers and code-page token tables. */
internal object WbxmlCodePages {
    private val PUBLIC_IDS =
        mapOf(
            0x01L to "WML 1.0",
            0x02L to "WML 1.1",
            0x03L to "WML 1.2",
            0x04L to "WML 1.3",
            0x05L to "SI 1.0",
            0x06L to "SL 1.0",
            0x07L to "CO 1.0",
            0x08L to "Channel 1.0",
            0x09L to "WML 1.4",
            0x0AL to "WTA 1.0",
            0x0BL to "PROV 1.0",
        )

    fun publicIdName(id: Long): String = PUBLIC_IDS[id] ?: "unknown(0x${id.toString(16).uppercase()})"

    fun kindForPublicId(id: Long): WbxmlKind =
        when (id) {
            0x05L -> WbxmlKind.SI
            0x06L -> WbxmlKind.SL
            0x07L -> WbxmlKind.CO
            0x0BL -> WbxmlKind.OMA_CP
            else -> WbxmlKind.AUTO
        }

    fun tag(kind: WbxmlKind, page: Int, token: Int): String? {
        val table = tagTable(kind, page) ?: return null
        return table[token]
    }

    private fun tagTable(kind: WbxmlKind, page: Int): Map<Int, String>? =
        when (kind) {
            WbxmlKind.SI -> {
                if (page == 0) {
                    mapOf(0x05 to "si", 0x06 to "indication", 0x07 to "info", 0x08 to "item")
                } else {
                    null
                }
            }

            WbxmlKind.SL -> {
                if (page == 0) mapOf(0x05 to "sl") else null
            }

            WbxmlKind.CO -> {
                if (page == 0) {
                    mapOf(0x05 to "co", 0x06 to "invalidate-object", 0x07 to "invalidate-service")
                } else {
                    null
                }
            }

            WbxmlKind.OMA_CP -> {
                when (page) {
                    0 -> mapOf(0x05 to "wap-provisioningdoc", 0x06 to "characteristic", 0x07 to "parm")
                    1 -> mapOf(0x06 to "characteristic", 0x07 to "parm")
                    else -> null
                }
            }

            WbxmlKind.AUTO -> {
                null
            }
        }

    fun attribute(kind: WbxmlKind, page: Int, token: Int): AttrSpec? {
        val table = attributeTable(kind, page) ?: return null
        return table[token]
    }

    private fun attributeTable(kind: WbxmlKind, page: Int): Map<Int, AttrSpec>? =
        when (kind) {
            WbxmlKind.SI -> {
                if (page == 0) {
                    mapOf(
                        0x05 to AttrSpec("action", "signal-none"),
                        0x06 to AttrSpec("action", "signal-low"),
                        0x07 to AttrSpec("action", "signal-medium"),
                        0x08 to AttrSpec("action", "signal-high"),
                        0x09 to AttrSpec("action", "delete"),
                        0x0A to AttrSpec("created"),
                        0x0B to AttrSpec("href"),
                        0x0C to AttrSpec("href", "http://"),
                        0x0D to AttrSpec("href", "http://www."),
                        0x0E to AttrSpec("href", "https://"),
                        0x0F to AttrSpec("href", "https://www."),
                        0x10 to AttrSpec("si-expires"),
                        0x11 to AttrSpec("si-id"),
                        0x12 to AttrSpec("class"),
                    )
                } else {
                    null
                }
            }

            WbxmlKind.SL -> {
                if (page == 0) {
                    mapOf(
                        0x05 to AttrSpec("action", "execute-low"),
                        0x06 to AttrSpec("action", "execute-high"),
                        0x07 to AttrSpec("action", "cache"),
                        0x08 to AttrSpec("href"),
                        0x09 to AttrSpec("href", "http://"),
                        0x0A to AttrSpec("href", "http://www."),
                        0x0B to AttrSpec("href", "https://"),
                        0x0C to AttrSpec("href", "https://www."),
                    )
                } else {
                    null
                }
            }

            WbxmlKind.CO -> {
                if (page == 0) {
                    mapOf(
                        0x05 to AttrSpec("uri"),
                        0x06 to AttrSpec("uri", "http://"),
                        0x07 to AttrSpec("uri", "http://www."),
                        0x08 to AttrSpec("uri", "https://"),
                        0x09 to AttrSpec("uri", "https://www."),
                    )
                } else {
                    null
                }
            }

            WbxmlKind.OMA_CP -> {
                cpAttributes(page)
            }

            WbxmlKind.AUTO -> {
                null
            }
        }

    private fun cpAttributes(page: Int): Map<Int, AttrSpec>? =
        when (page) {
            0 -> {
                val table = mutableMapOf<Int, AttrSpec>()
                table[0x05] = AttrSpec("name")
                table[0x06] = AttrSpec("value")
                table[0x45] = AttrSpec("version")
                table[0x46] = AttrSpec("version", "1.0")
                table[0x50] = AttrSpec("type")
                table[0x51] = AttrSpec("type", "PXLOGICAL")
                table[0x52] = AttrSpec("type", "PXPHYSICAL")
                table[0x53] = AttrSpec("type", "PORT")
                table[0x54] = AttrSpec("type", "VALIDITY")
                table[0x55] = AttrSpec("type", "NAPDEF")
                table[0x56] = AttrSpec("type", "BOOTSTRAP")
                table[0x57] = AttrSpec("type", "VENDORCONFIG")
                table[0x58] = AttrSpec("type", "CLIENTIDENTITY")
                table[0x59] = AttrSpec("type", "PXAUTHINFO")
                table[0x5A] = AttrSpec("type", "NAPAUTHINFO")
                table[0x5B] = AttrSpec("type", "ACCESS")
                val names =
                    listOf(
                        0x07 to "NAME",
                        0x08 to "NAP-ADDRESS",
                        0x09 to "NAP-ADDRTYPE",
                        0x0A to "CALLTYPE",
                        0x0B to "VALIDUNTIL",
                        0x0C to "AUTHTYPE",
                        0x0D to "AUTHNAME",
                        0x0E to "AUTHSECRET",
                        0x0F to "LINGER",
                        0x10 to "BEARER",
                        0x11 to "NAPID",
                        0x12 to "COUNTRY",
                        0x13 to "NETWORK",
                        0x14 to "INTERNET",
                        0x15 to "PROXY-ID",
                        0x16 to "PROXY-PROVIDER-ID",
                        0x17 to "DOMAIN",
                        0x18 to "PROVURL",
                        0x19 to "PXAUTH-TYPE",
                        0x1A to "PXAUTH-ID",
                        0x1B to "PXAUTH-PW",
                        0x1C to "STARTPAGE",
                        0x1D to "BASAUTH-ID",
                        0x1E to "BASAUTH-PW",
                        0x1F to "PUSHENABLED",
                        0x20 to "PXADDR",
                        0x21 to "PXADDRTYPE",
                        0x22 to "TO-NAPID",
                        0x23 to "PORTNBR",
                        0x24 to "SERVICE",
                        0x25 to "LINKSPEED",
                        0x26 to "DNLINKSPEED",
                        0x27 to "LOCAL-ADDR",
                        0x28 to "LOCAL-ADDRTYPE",
                        0x29 to "CONTEXT-ALLOW",
                        0x2A to "TRUST",
                        0x2B to "MASTER",
                        0x2C to "SID",
                        0x2D to "SOC",
                        0x2E to "WSP-VERSION",
                        0x2F to "PHYSICAL-PROXY-ID",
                        0x30 to "CLIENT-ID",
                        0x31 to "DELIVERY-ERR-SDU",
                        0x32 to "DELIVERY-ORDER",
                        0x33 to "TRAFFIC-CLASS",
                        0x34 to "MAX-SDU-SIZE",
                        0x35 to "MAX-BITRATE-UPLINK",
                        0x36 to "MAX-BITRATE-DNLINK",
                        0x37 to "RESIDUAL-BER",
                        0x38 to "SDU-ERROR-RATIO",
                        0x39 to "TRAFFIC-HANDL-PRIO",
                        0x3A to "TRANSFER-DELAY",
                        0x3B to "GUARANTEED-BITRATE-UPLINK",
                        0x3C to "GUARANTEED-BITRATE-DNLINK",
                        0x3D to "PXADDR-FQDN",
                        0x3E to "PROXY-PW",
                        0x3F to "PPGAUTH-TYPE",
                        0x47 to "PULLENABLED",
                        0x48 to "DNS-ADDR",
                        0x49 to "MAX-NUM-RETRY",
                        0x4A to "FIRST-RETRY-TIMEOUT",
                        0x4B to "REREG-THRESHOLD",
                        0x4C to "T-BIT",
                        0x4E to "AUTH-ENTITY",
                        0x4F to "SPI",
                    )
                for ((token, name) in names) table[token] = AttrSpec("name", name)
                table
            }

            1 -> {
                val table = mutableMapOf<Int, AttrSpec>()
                table[0x05] = AttrSpec("name")
                table[0x06] = AttrSpec("value")
                table[0x50] = AttrSpec("type")
                table[0x53] = AttrSpec("type", "PORT")
                table[0x55] = AttrSpec("type", "APPLICATION")
                table[0x56] = AttrSpec("type", "APPADDR")
                table[0x57] = AttrSpec("type", "APPAUTH")
                table[0x58] = AttrSpec("type", "CLIENTIDENTITY")
                table[0x59] = AttrSpec("type", "RESOURCE")
                table
            }

            else -> {
                null
            }
        }

    fun attributeValue(kind: WbxmlKind, page: Int, token: Int): String? {
        if (page != 0) return null
        return when (kind) {
            WbxmlKind.SI, WbxmlKind.SL, WbxmlKind.CO -> {
                when (token) {
                    0x85 -> ".com/"
                    0x86 -> ".edu/"
                    0x87 -> ".net/"
                    0x88 -> ".org/"
                    else -> null
                }
            }

            else -> {
                null
            }
        }
    }
}
