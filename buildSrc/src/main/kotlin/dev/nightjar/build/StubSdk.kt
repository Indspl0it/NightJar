package dev.nightjar.build

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File
import java.util.Properties

/**
 * Path of the `android.jar` that the plain Java modules (`:payload`, `:hiddenapi-stubs`, `:xposed-stubs`) compile
 * against. The platform level is the Gradle property `njStubSdk`. The SDK directory is the environment variable `ANDROID_HOME`.
 * Without it, the SDK directory is `sdk.dir` from `local.properties` (as AGP does).
 */
fun Project.stubAndroidJar(): Provider<String> {
    val level = providers.gradleProperty("njStubSdk")
    val localSdkDir =
        providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.map { text ->
            Properties().apply { load(text.reader()) }.getProperty("sdk.dir").orEmpty()
        }
    val sdkDir = providers.environmentVariable("ANDROID_HOME").orElse(localSdkDir)
    return sdkDir.zip(level) { dir, api -> "$dir/platforms/android-$api/android.jar" }
}

/**
 * [stubAndroidJar] as a file provider for task inputs and `zipTree`. The mapping lambda lives here and captures no
 * build-script object, so the configuration cache can store the tasks that use it (a `map { file(it) }` in a build
 * script captures the script and fails, VR01-B01).
 */
fun Project.stubAndroidJarFile(): Provider<RegularFile> = layout.file(stubAndroidJar().map { File(it) })
