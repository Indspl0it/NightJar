// Declaration-only stubs of the legacy Xposed API (82) that Vector/LSPosed provide at runtime. compileOnly; never packaged.
import dev.nightjar.build.stubAndroidJar

plugins { `java-library` }

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

// Platform level njStubSdk (gradle.properties), SDK from ANDROID_HOME or local.properties.
val androidJar = stubAndroidJar()

dependencies {
    compileOnly(files(androidJar))
}
