// Java payload that runs inside com.android.phone. Framework APIs only.
// The APK carries these classes: the app depends on this module and Vector/LSPosed load the APK (XposedEntry).
// build/payload/payload.dex is a gate artifact only; no runtime path loads it. R8 shrinks it (payload.pro; see
// ShrinkTask: obfuscation off, optimizer on) to measure the framework-only class rule and the 300 KiB size budget of
// what the phone process can reach.
import dev.nightjar.build.ShrinkTask
import dev.nightjar.build.stubAndroidJar
import dev.nightjar.build.stubAndroidJarFile

plugins { `java-library` }

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

// Platform level njStubSdk (gradle.properties), SDK from ANDROID_HOME or local.properties.
val stubJar = stubAndroidJar()
// The same path as a file provider; no script lambda maps it, so the configuration cache can store the tasks.
val stubJarFile = stubAndroidJarFile()

// The test compile classpath gets android.jar without its org.json stubs. In android.jar JSONException is a checked
// exception; the tests compile and run against the real org.json, where it is unchecked. Declaration order on the
// classpath cannot be trusted for this, so the stubs are removed.
val androidJarNoJson =
    tasks.register<Sync>("androidJarNoJson") {
        from(zipTree(stubJarFile)) { exclude("org/json/**") }
        into(layout.buildDirectory.dir("android-no-json"))
    }

dependencies {
    compileOnly(files(stubJar))
    compileOnly(project(":hiddenapi-stubs"))
    implementation(project(":contract"))
    implementation(project(":rules"))
    // The shared PDU/UDH/WSP/WBXML parser; R8 keeps only what the payload reaches.
    implementation(project(":pduparse"))

    // android.jar is compile-only: its methods throw "Stub!" at runtime, and putting it on the test
    // runtime classpath would shadow the real org.json implementation.
    testCompileOnly(files(androidJarNoJson))
    testImplementation(libs.org.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

// The payload reads the same fixtures as:rules, copied from:contract by the build (never duplicated).
val verdictFixturesDir = layout.buildDirectory.dir("verdict-fixtures")
val verdictFixtures =
    tasks.register<Sync>("verdictFixtures") {
        group = "verification"
        description = "Copies the shared verdict fixtures from :contract into the test runtime."
        from(rootProject.file("contract/src/test/resources")) { include("verdicts/**") }
        into(verdictFixturesDir)
    }
sourceSets["test"].resources.srcDir(verdictFixturesDir)
tasks.named("processTestResources") { dependsOn(verdictFixtures) }

// Program inputs: own classes + :contract + :rules + :pduparse jars. Never the stubs or android.jar.

val dexPayload =
    tasks.register<ShrinkTask>("dexPayload") {
        group = "nightjar"
        description = "Builds the gate-only payload.dex (R8 shrink, no obfuscation)."
        programFiles.from(
            sourceSets.main
                .get()
                .output.classesDirs,
            configurations.named("runtimeClasspath"),
        )
        androidJar.set(stubJarFile)
        keepRules.set(file("payload.pro"))
        minApi.set(35)
        outputDex.set(layout.buildDirectory.file("payload/payload.dex"))
    }

tasks.named("assemble") { dependsOn(dexPayload) }

val checkPayloadDex =
    tasks.register<dev.nightjar.build.CheckPayloadDexTask>("checkPayloadDex") {
        group = "verification"
        dex.set(dexPayload.flatMap { it.outputDex })
        maxBytes.set(300L * 1024)
    }
tasks.named("check") { dependsOn(checkPayloadDex) }
