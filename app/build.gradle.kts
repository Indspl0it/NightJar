// Companion app (features from on). No INTERNET permission.
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

val njAppId = providers.gradleProperty("njAppId").get()

// Release material lives outside the repo. `local.properties` (never tracked) names the keystore and
// alias; the two passwords come from the environment so no secret is ever written to a file.
val njLocalProperties =
    rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }
val njKeystorePath: String? =
    providers.environmentVariable("NJ_KEYSTORE").orNull ?: njLocalProperties?.getProperty("nj.keystore")
val njKeyAlias: String? =
    providers.environmentVariable("NJ_KEY_ALIAS").orNull ?: njLocalProperties?.getProperty("nj.keyAlias")
val njStorePassword: String? = providers.environmentVariable("NJ_KEYSTORE_PASSWORD").orNull
val njKeyPassword: String? = providers.environmentVariable("NJ_KEY_PASSWORD").orNull

// All four values and the keystore file must be present; a partial set would fail validateSigningRelease, so the
// release stays unsigned. A partial set gets a warning that names the missing items (never a value).
val njCanSign =
    njKeystorePath != null && njKeyAlias != null && njStorePassword != null && njKeyPassword != null &&
        file(njKeystorePath).isFile
if (!njCanSign && (njKeystorePath != null || njKeyAlias != null)) {
    val missing =
        listOfNotNull(
            "keystore path (NJ_KEYSTORE or nj.keystore)".takeIf { njKeystorePath == null },
            "keystore file".takeIf { njKeystorePath != null && !file(njKeystorePath).isFile },
            "key alias (NJ_KEY_ALIAS or nj.keyAlias)".takeIf { njKeyAlias == null },
            "NJ_KEYSTORE_PASSWORD".takeIf { njStorePassword == null },
            "NJ_KEY_PASSWORD".takeIf { njKeyPassword == null },
        )
    logger.warn("Release signing material is not complete. The release APK stays unsigned. Missing: ${missing.joinToString()}")
}

// Robolectric downloads its android-all jars to the Maven local repository (~/.m2 by default). When the environment
// variable ROBOLECTRIC_MAVEN_REPO is set, the jars go to that folder instead.
val njRobolectricRepo: String? =
    providers.environmentVariable("ROBOLECTRIC_MAVEN_REPO").orNull?.let { File(it).absolutePath }

base.archivesName.set("NightJar-${rootProject.extra["njVersionName"]}")

android {
    namespace = "dev.nightjar.app"
    compileSdk = 37

    defaultConfig {
        applicationId = njAppId
        minSdk = 35
        targetSdk = 36
        versionCode = rootProject.extra["njVersionCode"] as Int
        versionName = rootProject.extra["njVersionName"] as String
        // A rooted arm64 phone is the only target; the other three SQLCipher builds were 5.6 MB of dead weight.
        ndk { abiFilters += "arm64-v8a" }
    }

    androidResources { localeFilters += "en" }

    signingConfigs {
        if (njCanSign) {
            create("release") {
                storeFile = file(checkNotNull(njKeystorePath))
                storePassword = njStorePassword
                keyAlias = njKeyAlias
                keyPassword = njKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release builds are signed only from CI/user-supplied material; without a keystore the
            // release stays unsigned instead of silently using the debug key.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // AGP 9 generates en-XA / ar-XB pseudo-locales for debuggable variants automatically;
    // the `androidResources.pseudoLocalesEnabled` flag of older AGP releases no longer exists.

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // MavenDependencyResolver of Robolectric reads `maven.repo.local`.
            njRobolectricRepo?.let { repo -> it.systemProperty("maven.repo.local", repo) }
            // Screenshots: the default run records one image per screen. `-Pnj.shots.all=true` records every state.
            it.systemProperty("nj.shots.all", providers.gradleProperty("nj.shots.all").getOrElse("false"))
        }
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    // AGP adds a dependency list, encrypted with a Google key, to the APK signing block. Only Google Play can read
    // it, and F-Droid rejects APKs that carry it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        checkDependencies = false
    }
}

// JVM screenshot tests (Roborazzi on Robolectric, src/test/.../screenshot). The plain `test` task runs
// them without writing or comparing images, so a missing reference image never fails a gate. Record the PNGs with
// `./gradlew :app:recordRoborazziDebug` (or `:app:testDebugUnitTest -Proborazzi.test.record=true`).
roborazzi { outputDir.set(layout.buildDirectory.dir("outputs/roborazzi")) }

// Schema JSON is exported and checked in (app/schemas) so migrations are reviewable.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":contract"))
    implementation(project(":rules"))
    implementation(project(":decoder"))
    // The payload runs inside com.android.phone through the Xposed API of Vector/LSPosed (stubs are compile-only).
    implementation(project(":payload"))
    compileOnly(project(":xposed-stubs"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation and adaptive layout
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    // Settings
    implementation(libs.datastore.preferences)
    // Root operations
    implementation(libs.libsu.core)
    implementation(libs.libsu.io)
    // Storage
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)
    implementation(libs.paging.compose)
    // Housekeeping
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // :rules serialises through org.json; the Android stub throws in plain JVM tests.
    testImplementation(libs.org.json)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    // Screenshot tests. Test scope only: nothing reaches an APK. ui-test-manifest is left out on purpose: it
    // must be a debugImplementation (lint TestManifestGradleConfiguration, a Correctness error here) and would add an
    // activity to the debug APK. roborazzi-compose registers its own host activity with Robolectric.
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// Manifest permission check and, for release, the APK size and Xposed metadata checks, per variant.
val srsPermissions =
    listOf(
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.USE_BIOMETRIC",
        "android.permission.QUERY_ALL_PACKAGES",
    )
// Declared by AndroidX WorkManager and used by its own runtime (job wake locks, expedited work, constraint tracking).
// Nightjar schedules no network-constrained work, but WorkManager's scheduler still needs these.
val libraryDeclaredPermissions =
    listOf(
        "android.permission.WAKE_LOCK",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.ACCESS_NETWORK_STATE",
    )
extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
    val cap = variant.name.replaceFirstChar { it.uppercase() }
    tasks.register<dev.nightjar.build.CheckManifestTask>("checkManifest$cap") {
        group = "verification"
        manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
        allowedPermissions.set(srsPermissions)
        libraryPermissions.set(libraryDeclaredPermissions)
        applicationId.set(variant.applicationId)
    }
    if (variant.buildType == "release") {
        tasks.register<dev.nightjar.build.CheckApkSizeTask>("checkApkSize$cap") {
            group = "verification"
            apkDir.from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            maxBytes.set(15L * 1024 * 1024)
        }
        tasks.register<dev.nightjar.build.CheckXposedMetadataTask>("checkXposedMetadata$cap") {
            group = "verification"
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            valueFiles.from(
                layout.projectDirectory
                    .dir("src/main/res/values")
                    .asFileTree
                    .matching { include("*.xml") },
            )
            apkDir.from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            requiredScope.set("com.android.phone")
        }
    }
}
