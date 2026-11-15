package dev.nightjar.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Contract literal check: IPC method names, Bundle keys, the binder descriptor and the wire names of the contract
 * enums are defined only in `:contract`. Any other source that spells one of those values as a string literal fails
 * the build. Waive a line with `// contract-ok: <reason>`.
 *
 * Two kinds of contract file give protected values:
 * - [contractFiles]: `static final String X = "value"` constants (Ipc.java, Control.java).
 * - [wireEnumFiles]: enum constants with a wire name, `NAME("wire")` (ClassId, Fate, AnomalyCode, TraceStage).
 * Values shorter than 5 characters are not protected: short or generic words would give false positives in UI code.
 */
@DisableCachingByDefault(because = "verification")
abstract class CheckContractLiteralsTask : DefaultTask() {
    /** The contract files whose `= "value"` constants are protected. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractFiles: ConfigurableFileCollection

    /** The contract enum files whose wire names are protected. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wireEnumFiles: ConfigurableFileCollection

    /** Source roots to scan. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val constant = Regex("""static\s+final\s+String\s+\w+\s*=\s*"([^"]+)"""")
        val wireName = Regex("""^\s*[A-Z][A-Z0-9_]*\s*\(\s*"([^"]+)"""", RegexOption.MULTILINE)
        val protectedValues =
            (
                contractFiles.files.flatMap { f -> constant.findAll(f.readText()).map { it.groupValues[1] }.toList() } +
                    wireEnumFiles.files.flatMap { f -> wireName.findAll(f.readText()).map { it.groupValues[1] }.toList() }
            ).filter { it.length >= MIN_LENGTH }
                .toSet()
        val problems = mutableListOf<String>()
        val files = sources.asFileTree.matching { include("**/*.java", "**/*.kt") }.files
        files.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (line.contains("contract-ok:")) return@forEachIndexed
                protectedValues.filter { line.contains("\"$it\"") }.forEach { v ->
                    problems += "${f.path}:${i + 1}: literal \"$v\" (use the :contract constant)"
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException("Contract literal check failed:\n  " + problems.joinToString("\n  "))
        logger.lifecycle("contract literals ok: ${protectedValues.size} protected values, ${files.size} files scanned")
    }

    private companion object {
        const val MIN_LENGTH = 5
    }
}
