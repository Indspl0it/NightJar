// Shared PDU, UDH, WSP and WBXML parser. One parser for:payload (inside com.android.phone) and:decoder.
// Pure Java so the payload dex stays small. No Android dependency; depends only on:contract.
// Every entry point is total: it returns facts plus AnomalyCode values and never throws.
plugins { `java-library` }

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    api(project(":contract"))
    testImplementation(libs.org.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// CbCategoriesTest reads testdata/scenarios.json. Declare it as an input so that a scenario change runs the
// test again, and give the path so that the test does not depend on the working directory.
val scenariosFile = rootProject.file("testdata/scenarios.json")
tasks.test {
    useJUnitPlatform()
    inputs.file(scenariosFile).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("nj.scenarios", scenariosFile.absolutePath)
}

// The anomaly fixtures live once, in the decoder corpus; the build copies them into this test runtime.
val anomalyFixturesDir = layout.buildDirectory.dir("anomaly-fixtures")
val anomalyFixtures =
    tasks.register<Copy>("anomalyFixtures") {
        group = "verification"
        description = "Copies the anomaly fixtures from the decoder corpus into the test runtime."
        from(rootProject.file("decoder/src/test/resources/corpus")) { include("anomalies.json") }
        into(anomalyFixturesDir)
    }
sourceSets["test"].resources.srcDir(anomalyFixturesDir)
tasks.named("processTestResources") { dependsOn(anomalyFixtures) }
