package dev.nightjar.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Xposed metadata check: the release APK carries the legacy Xposed module metadata that Vector/LSPosed read.
 * The manifest names the module, the API level, a description and a scope array. The scope array holds the target
 * package. The APK holds the entry file `assets/xposed_init`, and the class that file names stays in the dex.
 *
 * The scope check in the APK is a smoke check only: it finds the scope bytes anywhere in `resources.arsc`. The exact
 * check is the one on the source `values` files.
 */
@DisableCachingByDefault(because = "verification")
abstract class CheckXposedMetadataTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** Source `values` XML files that define the scope string-array. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val valueFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDir: ConfigurableFileCollection

    @get:Input abstract val requiredScope: Property<String>

    @TaskAction
    fun check() {
        val apks = apkDir.asFileTree.matching { include("**/*.apk") }.files
        if (apks.isEmpty()) throw GradleException("Xposed metadata check: no release APK found")
        val problems = mutableListOf<String>()
        val scopeArray = checkManifest(manifest.get().asFile, requiredScope.get(), valueFiles.files, problems)
        apks.forEach { checkApk(it, requiredScope.get(), scopeArray != null, problems, temporaryDir) }
        if (problems.isNotEmpty()) throw GradleException("Xposed metadata check failed:\n  " + problems.joinToString("\n  "))
        logger.lifecycle("Xposed metadata check ok: ${apks.joinToString { it.name }} scope=${requiredScope.get()}")
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** Checks the merged manifest. Returns the scope array name, or null when it has a problem. */
        fun checkManifest(manifest: File, scope: String, valueFiles: Collection<File>, problems: MutableList<String>): String? {
            val doc =
                DocumentBuilderFactory
                    .newInstance()
                    .apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(manifest)
            val meta = doc.getElementsByTagName("meta-data")
            val entries = (0 until meta.length).map { meta.item(it) as Element }.associateBy { it.getAttributeNS(ANDROID_NS, "name") }

            fun value(e: Element?) = e?.getAttributeNS(ANDROID_NS, "value").orEmpty()
            if (value(entries["xposedmodule"]) != "true") problems += "manifest meta-data xposedmodule must be true"
            if (value(entries["xposedminversion"]).toIntOrNull() == null) problems += "manifest meta-data xposedminversion must be a number"
            if (value(entries["xposeddescription"]).isBlank()) problems += "manifest meta-data xposeddescription is missing or empty"
            val ref = entries["xposedscope"]?.getAttributeNS(ANDROID_NS, "resource").orEmpty()
            if (!ref.startsWith("@array/")) {
                problems += "manifest meta-data xposedscope must reference an @array resource"
                return null
            }
            val arrayName = ref.removePrefix("@array/")
            val stringValues = valueFiles.flatMap { strings(it).entries }.associate { it.key to it.value }
            // An item can be a literal or a @string/ reference to a string in the same value files.
            val items =
                valueFiles.flatMap { stringArray(it, arrayName) ?: emptyList() }.map { item ->
                    if (item.startsWith("@string/")) stringValues[item.removePrefix("@string/")] ?: item else item
                }
            if (scope !in items) {
                problems += "scope array $arrayName does not contain $scope (found: $items)"
                return null
            }
            return arrayName
        }

        private fun strings(file: File): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
        }

        private fun stringArray(file: File, name: String): List<String>? {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val arrays = doc.getElementsByTagName("string-array")
            val array =
                (0 until arrays.length)
                    .map { arrays.item(it) as Element }
                    .firstOrNull { it.getAttribute("name") == name } ?: return null
            val items = array.getElementsByTagName("item")
            return (0 until items.length).map { items.item(it).textContent.trim() }
        }

        /** Checks the APK entry file, the entry class in the dex and the scope string in the compiled resources. */
        fun checkApk(apk: File, scope: String, scopeKnown: Boolean, problems: MutableList<String>, scratchDir: File) {
            ZipFile(apk).use { zip ->
                val entryFile = zip.getEntry("assets/xposed_init")
                if (entryFile == null) {
                    problems += "${apk.name}: assets/xposed_init is missing"
                } else {
                    val classes =
                        zip
                            .getInputStream(entryFile)
                            .bufferedReader()
                            .readLines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                    if (classes.isEmpty()) problems += "${apk.name}: assets/xposed_init names no class"
                    val defined = DexTypes.definedClassesInApk(apk, scratchDir)
                    classes.forEach { c ->
                        if ("L${c.replace('.', '/')};" !in defined) {
                            problems += "${apk.name}: xposed_init class $c is not in the dex (R8 removed it?)"
                        }
                    }
                }
                val arsc = zip.getEntry("resources.arsc")
                if (arsc == null) {
                    problems += "${apk.name}: resources.arsc is missing"
                } else if (scopeKnown) {
                    val bytes = zip.getInputStream(arsc).readBytes()
                    if (indexOf(bytes, scope.toByteArray(Charsets.UTF_8)) < 0 && indexOf(bytes, scope.toByteArray(Charsets.UTF_16LE)) < 0) {
                        problems += "${apk.name}: scope $scope is not in resources.arsc"
                    }
                }
            }
        }

        private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
            outer@ for (i in 0..data.size - pattern.size) {
                for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
