package dev.nightjar.decoder

/** OMA Client Provisioning WBXML and its plain summary (-09). */
object OmaCp {
    /** One parm with the characteristics around it, innermost last (type and instance number of each). */
    private data class Parm(
        val path: List<Pair<String, Int>>,
        val name: String,
        val value: String,
        val offset: Int,
        val length: Int,
    ) {
        val characteristic: String get() = path.lastOrNull()?.first ?: ""
    }

    fun decode(bytes: ByteArray): Field = guarded("oma_cp", bytes) { build(bytes, 0) }

    fun decode(hex: String): Field = guardedHex("oma_cp", hex) { decode(it) }

    /** A document at absolute [baseOffset], for a tree nested in a push. */
    internal fun decodeAt(
        bytes: ByteArray,
        baseOffset: Int,
    ): Field = guarded("oma_cp", bytes) { build(bytes, baseOffset) }

    private fun build(
        bytes: ByteArray,
        baseOffset: Int,
    ): Field {
        val wbxml = Wbxml.decodeAt(bytes, baseOffset, WbxmlKind.OMA_CP)
        return field("oma_cp", null, baseOffset, bytes.size, listOf(wbxml, summary(wbxml)))
    }

    /**
     * The plain summary (OMA-WAP-ProvCont). `apn` is the NAP-ADDRESS of a NAPDEF whose NAP-ADDRTYPE is APN or not
     * given, `name` is the NAME of that NAPDEF. `proxy` is a PXADDR with the PORTNBR of the same
     * PXLOGICAL or PXPHYSICAL.
     */
    fun summary(wbxmlField: Field): Field {
        val parms = mutableListOf<Parm>()
        val characteristics = mutableListOf<String>()
        val document = wbxmlField.child("document") ?: wbxmlField
        val counter = intArrayOf(0)
        for (element in document.children) walk(element, emptyList(), parms, characteristics, counter)
        val napdefs = parms.filter { it.characteristic == "NAPDEF" }.groupBy { it.path.last() }
        val apnNapdef =
            napdefs.values.firstOrNull { group ->
                group.any { it.name == "NAP-ADDRESS" } &&
                    group.none { it.name == "NAP-ADDRTYPE" && !it.value.equals("APN", ignoreCase = true) }
            }
        val apnParm = apnNapdef?.firstOrNull { it.name == "NAP-ADDRESS" }
        val nameParm = (apnNapdef ?: napdefs.values.firstOrNull())?.firstOrNull { it.name == "NAME" }
        val proxyParm = parms.firstOrNull { it.name == "PXADDR" }
        val proxy =
            proxyParm?.let { parm ->
                val owner = parm.path.lastOrNull { it.first == "PXPHYSICAL" || it.first == "PXLOGICAL" }
                val port = owner?.let { o -> parms.firstOrNull { it.name == "PORTNBR" && o in it.path }?.value }
                if (port != null) "${parm.value}:$port" else parm.value
            }
        val homeParm =
            parms.firstOrNull { it.name == "STARTPAGE" }
                ?: parms.firstOrNull { it.name == "ADDR" }
                ?: parms.firstOrNull { it.name == "URI" }
        val children = mutableListOf<Field>()
        apnParm?.let { children.add(field("apn", it.value, it.offset, it.length)) }
        nameParm?.let { children.add(field("name", it.value, it.offset, it.length)) }
        proxyParm?.let { parm -> proxy?.let { children.add(field("proxy", it, parm.offset, parm.length)) } }
        homeParm?.let { children.add(field("home_page", it.value, it.offset, it.length)) }
        children.add(field("characteristic_types", characteristics.distinct().joinToString(","), document.offset, 0))
        children.add(field("parm_count", parms.size, document.offset, 0))
        return group("summary", document.offset, 0, children)
    }

    private fun walk(
        field: Field,
        path: List<Pair<String, Int>>,
        parms: MutableList<Parm>,
        characteristics: MutableList<String>,
        counter: IntArray,
    ) {
        var current = path
        if (field.name == "characteristic") {
            val type = field.child("@type")?.value?.toString() ?: ""
            counter[0]++
            current = path + (type to counter[0])
            if (type.isNotEmpty()) characteristics.add(type)
        }
        if (field.name == "parm") {
            val name = field.child("@name")?.value?.toString()
            val value = field.child("@value")?.value?.toString()
            if (name != null) parms.add(Parm(current, name, value ?: "", field.offset, field.length))
        }
        for (child in field.children) {
            if (child.name.startsWith("@") || child.name.startsWith("#")) continue
            walk(child, current, parms, characteristics, counter)
        }
    }
}
