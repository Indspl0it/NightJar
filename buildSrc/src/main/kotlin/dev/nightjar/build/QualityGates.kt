package dev.nightjar.build

import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Document
import org.w3c.dom.Element

// Build checks for the payload dex, the manifest, log masking and the release APK size.

/** Payload dex check: payload.dex references only framework packages, defines only dev.nightjar classes, ≤ 300 KB. */
@DisableCachingByDefault(because = "verification")
abstract class CheckPayloadDexTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val dex: RegularFileProperty
    @get:Input abstract val maxBytes: Property<Long>

    @TaskAction
    fun check() {
        val f = dex.get().asFile
        val problems = mutableListOf<String>()
        if (f.length() > maxBytes.get()) problems += "${f.name} is ${f.length()} bytes (max ${maxBytes.get()})"
        val d = DexTypes(f)
        val allowed = listOf("Ljava/", "Ljavax/", "Landroid/", "Lcom/android/internal/", "Lorg/json/", "Ldalvik/", "Ldev/nightjar/", "Lorg/xmlpull/")
        d.types.map { it.trimStart('[') }.filter { it.startsWith("L") }.filterNot { t -> allowed.any { t.startsWith(it) } }
            .forEach { problems += "references non-framework type $it" }
        d.definedClasses.filterNot { it.startsWith("Ldev/nightjar/") }
            .forEach { problems += "defines class outside dev.nightjar (stub leaked?): $it" }
        if (problems.isNotEmpty()) throw GradleException("Payload dex check failed for ${f.name}:\n  " + problems.joinToString("\n  "))
        logger.lifecycle("Payload dex check ok: ${f.name} ${f.length()} bytes, ${d.definedClasses.size} classes")
    }
}

/** Manifest check: merged manifest has no INTERNET, only allowed permissions, local backup only, direct-boot-aware providers. */
@DisableCachingByDefault(because = "verification")
abstract class CheckManifestTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifest: RegularFileProperty
    @get:Input abstract val allowedPermissions: ListProperty<String>

    /**
     * Permissions our dependencies declare and their runtime code uses (WorkManager: WAKE_LOCK, FOREGROUND_SERVICE,
     * ACCESS_NETWORK_STATE). Any other permission is a finding.
     */
    @get:Input abstract val libraryPermissions: ListProperty<String>
    @get:Input abstract val applicationId: Property<String>

    @TaskAction
    fun check() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest.get().asFile)
        val problems = mutableListOf<String>()
        val perms = declaredPermissions(doc)
        // androidx.core adds this signature-level permission scoped to our own package.
        val ownSignature = "${applicationId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        if ("android.permission.INTERNET" in perms) problems += "INTERNET permission declared"
        perms
            .filterNot { it in allowedPermissions.get() || it in libraryPermissions.get() || it == ownSignature }
            .forEach { problems += "permission not allowed: $it" }
        val app = doc.getElementsByTagName("application").item(0) as Element
        // Backups on (Swift Backup, device transfer); cloud backup excluded by dataExtractionRules.
        if (app.getAttributeNS(ns, "allowBackup") != "true") problems += "android:allowBackup must be true"
        if (app.getAttributeNS(ns, "dataExtractionRules").isBlank()) problems += "android:dataExtractionRules must exclude cloud backup"
        val providers = doc.getElementsByTagName("provider")
        for (i in 0 until providers.length) {
            val p = providers.item(i) as Element
            val name = p.getAttributeNS(ns, "name")
            if (name.startsWith("dev.nightjar") && p.getAttributeNS(ns, "directBootAware") != "true") problems += "provider $name must be directBootAware"
        }
        if (problems.isNotEmpty()) throw GradleException("Manifest check failed:\n  " + problems.joinToString("\n  "))
        logger.lifecycle("Manifest check ok: permissions $perms")
    }
}

/**
 * The permission names of a manifest. It reads uses-permission and uses-permission-sdk-23, because a dependency
 * can add a permission with either element.
 */
private fun declaredPermissions(doc: Document): List<String> {
    val ns = "http://schemas.android.com/apk/res/android"
    return listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
        val nodes = doc.getElementsByTagName(tag)
        (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ns, "name") }
    }
}

/**
 * Log masking check: log calls never pass raw text/number/pdu values (heuristic; waive a call with `// mask-ok: <reason>` on one of
 * its lines).
 *
 * A log call is a call on `PLog`, `ALog`, `Log`, `XposedBridge`, on a logger instance named `log` or
 * `logger`, or a bare `log(...)`. The check reads the whole call, also when its arguments continue on the next lines.
 * The text of string literals is ignored, but Kotlin template references (`$x`, `${x}`) in them count. An argument
 * inside `Masker.xxx(...)` is masked and does not count, and neither does a length or size (`text.length`,
 * `pdu.size`). The declaration `fun log(...)` is not a call.
 */
@DisableCachingByDefault(because = "verification")
abstract class CheckLogMaskingTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        val files = sources.asFileTree.matching { include("**/*.java", "**/*.kt") }.files
        files.forEach { f -> problems += findUnmaskedLogCalls(f.path, f.readLines()) }
        if (problems.isNotEmpty()) {
            throw GradleException("Log masking check failed; mask these or add // mask-ok: <reason>:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("Log masking check ok: ${files.size} files scanned")
    }

    companion object {
        private val CALL = Regex("""(\b(?:PLog|ALog|Log|XposedBridge|log|logger)\.[a-zA-Z]+|(?<![\w.])log)\s*\(""")
        private val RISKY =
            Regex(
                """\b(text|body|pdu|pdus|number|originator|address|destination|getOriginatingAddress|getDisplayOriginatingAddress|getMessageBody|getDisplayMessageBody)\b""",
            )

        /** A double-quoted string literal on one line. */
        private val STRING = Regex(""""(?:\\.|[^"\\])*"""")
        private val TEMPLATE = Regex("""\$\{[^}]*\}|\$[A-Za-z_]\w*""")
        private val MASKED = Regex("""Masker\.\w+\([^()]*\)""")
        private val SIZE_ONLY = Regex("""\b\w+\.(?:length|size)\b(?:\(\))?""")
        private val DECLARATION = Regex("""\bfun\s+log\s*\(""")
        private const val MAX_CALL_LINES = 8

        /** Returns `path:line: call` for each log call in [lines] that passes a risky value without the masker. */
        fun findUnmaskedLogCalls(
            path: String,
            lines: List<String>,
        ): List<String> {
            val out = mutableListOf<String>()
            lines.forEachIndexed { i, line ->
                val m = CALL.find(line) ?: return@forEachIndexed
                if (DECLARATION.containsMatchIn(line)) return@forEachIndexed
                val callLines = mutableListOf(line.substring(m.range.last + 1))
                var depth = 1 + depthChange(callLines[0])
                var k = i
                while (depth > 0 && k + 1 < lines.size && k - i + 1 < MAX_CALL_LINES) {
                    k++
                    callLines += lines[k]
                    depth += depthChange(lines[k])
                }
                if ((i..k).any { lines[it].contains("mask-ok:") }) return@forEachIndexed
                var args = callLines.joinToString(" ")
                args = STRING.replace(args) { s -> TEMPLATE.findAll(s.value).joinToString(" ") { it.value } }
                var previous: String
                do {
                    previous = args
                    args = MASKED.replace(args, "")
                } while (args != previous)
                args = SIZE_ONLY.replace(args, "")
                if (RISKY.containsMatchIn(args)) out += "$path:${i + 1}: ${line.trim()}"
            }
            return out
        }

        /** Open minus close parentheses outside string literals. */
        private fun depthChange(text: String): Int {
            val code = STRING.replace(text, "")
            return code.count { it == '(' } - code.count { it == ')' }
        }
    }
}

/** APK size check: release APK ≤ 15 MB. */
@DisableCachingByDefault(because = "verification")
abstract class CheckApkSizeTask : DefaultTask() {
    @get:InputFiles abstract val apkDir: ConfigurableFileCollection
    @get:Input abstract val maxBytes: Property<Long>

    @TaskAction
    fun check() {
        val apks = apkDir.asFileTree.matching { include("**/*.apk") }.files
        if (apks.isEmpty()) throw GradleException("APK size check: no release APK found")
        val big = apks.filter { it.length() > maxBytes.get() }
        if (big.isNotEmpty()) throw GradleException("APK size check failed: " + big.joinToString { "${it.name}=${it.length()} bytes" })
        logger.lifecycle("APK size check ok: " + apks.joinToString { "${it.name}=${it.length()} bytes" })
    }
}
