package dev.nightjar.build

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.gradle.api.GradleException

/**
 * Minimal dex reader: referenced type descriptors and defined class descriptors.
 *
 * Strings are MUTF-8 in a dex. This reader decodes them as UTF-8, which is correct for class and type names (ASCII
 * plus normal BMP characters); a name with an embedded NUL or a supplementary character would decode differently.
 */
class DexTypes(file: File) {
    private val name = file.name
    private val buf: ByteBuffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

    init {
        val magic = if (buf.capacity() >= HEADER_SIZE) ByteArray(4).also { buf.get(0, it) }.toString(Charsets.US_ASCII) else ""
        if (magic != "dex\n") throw GradleException("$name is not a dex file")
    }

    private fun u32(off: Int): Int {
        if (off < 0 || off + 4 > buf.capacity()) throw invalid()
        return buf.getInt(off)
    }

    private fun byteAt(off: Int): Int {
        if (off < 0 || off >= buf.capacity()) throw invalid()
        return buf.get(off).toInt()
    }

    private fun invalid() = GradleException("$name is not a valid dex file (an offset is out of range)")

    private fun string(idx: Int): String {
        val stringIdsOff = u32(0x3C)
        var p = u32(stringIdsOff + idx * 4)
        while (byteAt(p) and 0x80 != 0) p++ // skip the uleb128 utf16 length
        p++
        val start = p
        while (byteAt(p) != 0) p++
        return ByteArray(p - start).also { buf.get(start, it) }.toString(Charsets.UTF_8)
    }

    /** Every type descriptor referenced by the dex, e.g. `Ljava/lang/String;`. */
    val types: List<String> by lazy {
        val size = u32(0x40)
        val off = u32(0x44)
        (0 until size).map { string(u32(off + it * 4)) }
    }

    /** Descriptors of classes defined in the dex. */
    val definedClasses: List<String> by lazy {
        val size = u32(0x60)
        val off = u32(0x64)
        (0 until size).map { idx ->
            val typeIdx = u32(off + idx * CLASS_DEF_SIZE)
            types.getOrNull(typeIdx) ?: throw invalid()
        }
    }

    companion object {
        private const val HEADER_SIZE = 0x70
        private const val CLASS_DEF_SIZE = 32
        private val DEX_ENTRY = Regex("classes\\d*\\.dex")

        /** Descriptors of the classes defined in all `classes*.dex` entries of [apk]. [scratchDir] holds temp copies. */
        fun definedClassesInApk(
            apk: File,
            scratchDir: File,
        ): Set<String> =
            ZipFile(apk).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { it.name.matches(DEX_ENTRY) }
                    .flatMap { e ->
                        scratchDir.mkdirs()
                        val tmp = File.createTempFile("dex", ".dex", scratchDir)
                        try {
                            zip.getInputStream(e).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                            DexTypes(tmp).definedClasses
                        } finally {
                            tmp.delete()
                        }
                    }.toSet()
            }
    }
}
