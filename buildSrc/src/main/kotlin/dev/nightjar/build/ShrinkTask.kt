package dev.nightjar.build

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Turns the payload's program classes into exactly one dex with R8.
 *
 * The dex is a check artifact only: no runtime path loads it. Vector/LSPosed load the payload classes from the
 * app APK, which gets them through `implementation(project(":payload"))` and shrinks them with the app's R8 rules.
 * The gate dex proves that the payload code references only framework types and that its size stays inside the
 * 300 KiB budget.
 *
 * R8 runs with `-dontobfuscate` and its optimizer ON (payload/payload.pro): unreachable code is removed and dead
 * branches, constants and small accessors are folded. Names stay readable in the check output. Framework classes come
 * from the android.jar library input, so overrides such as `Binder.onTransact` are retained.
 */
abstract class ShrinkTask : DefaultTask() {
    @get:InputFiles @get:Classpath
    abstract val programFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val androidJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val keepRules: RegularFileProperty

    @get:Input abstract val minApi: Property<Int>

    @get:OutputFile abstract val outputDex: RegularFileProperty

    @TaskAction
    fun run() {
        val work =
            File(temporaryDir, "out").apply {
                deleteRecursively()
                mkdirs()
            }
        // R8 takes jars or .class files, not directories: expand directories to their .class files.
        val inputs =
            programFiles.files.filter { it.exists() }.flatMap { f ->
                if (f.isDirectory) {
                    f.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
                } else {
                    listOf(f)
                }
            }
        check(inputs.isNotEmpty()) { "no program files for the payload dex" }
        val command =
            R8Command
                .builder()
                .addProgramFiles(inputs.map { it.toPath() })
                .addLibraryFiles(androidJar.get().asFile.toPath())
                .addProguardConfigurationFiles(keepRules.get().asFile.toPath())
                .setMode(CompilationMode.RELEASE)
                .setMinApiLevel(minApi.get())
                .setOutput(work.toPath(), OutputMode.DexIndexed)
                .build()
        R8.run(command)
        val dexes = work.listFiles { f -> f.name.endsWith(".dex") }.orEmpty()
        check(dexes.size == 1) { "payload must fit in one dex, got ${dexes.map { it.name }}" }
        dexes[0].copyTo(outputDex.get().asFile, overwrite = true)
    }
}
