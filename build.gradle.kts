plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

val versionProps =
    java.util.Properties().apply {
        rootProject.file("version.properties").inputStream().use { load(it) }
    }
extra["njVersionName"] = versionProps.getProperty("versionName")
extra["njVersionCode"] = versionProps.getProperty("versionCode").toInt()

tasks.register<dev.nightjar.build.CheckLogMaskingTask>("checkLogMasking") {
    group = "verification"
    sources.from(
        "payload/src",
        "app/src",
        "contract/src/main",
        "rules/src/main",
        "decoder/src/main",
        "pduparse/src/main",
    )
}

// Formatting (Spotless + ktlint) and static analysis (detekt). Lint runs per Android module.
spotless {
    kotlin {
        target(
            fileTree(".") {
                include("**/src/**/*.kt")
                exclude("**/build/**", "buildSrc/.gradle/**")
            },
        )
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target(
            fileTree(".") {
                include("*.kts", "**/*.gradle.kts")
                exclude("**/build/**")
            },
        )
        ktlint(libs.versions.ktlint.get())
    }
    java {
        target(
            fileTree(".") {
                include("**/src/**/*.java")
                exclude("**/build/**")
            },
        )
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt.yml"))
    source.setFrom(
        "app/src/main/java",
        "decoder/src/main/kotlin",
    )
}

// Contract keys only in:contract.
tasks.register<dev.nightjar.build.CheckContractLiteralsTask>("checkContractLiterals") {
    group = "verification"
    val contractDir = "contract/src/main/java/dev/nightjar/contract"
    contractFiles.from("$contractDir/Ipc.java", "$contractDir/Control.java")
    wireEnumFiles.from(
        "$contractDir/ClassId.java",
        "$contractDir/Fate.java",
        "$contractDir/AnomalyCode.java",
        "$contractDir/TraceStage.java",
    )
    sources.from(
        "payload/src/main",
        "app/src/main",
        "rules/src/main",
        "decoder/src/main",
        "pduparse/src/main",
    )
}

// All quality checks in one task: format, static analysis, lint, unit tests, payload dex, manifest, Xposed metadata,
// log masking and release APK size. CI runs this task.
tasks.register("verify") {
    group = "verification"
    description = "Runs all quality checks."
    dependsOn(
        "spotlessCheck",
        "detekt",
        "checkContractLiterals",
        "checkLogMasking",
        ":app:lintDebug",
        ":app:test",
        ":payload:test",
        ":contract:test",
        ":rules:test",
        ":decoder:test",
        ":pduparse:test",
        ":payload:checkPayloadDex",
        ":app:checkManifestDebug",
        ":app:checkManifestRelease",
        ":app:checkXposedMetadataRelease",
        ":app:checkApkSizeRelease",
    )
}
