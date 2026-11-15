// Shared rule engine: payload verdicts and app validation/preview. No runtime dependencies.
plugins { `java-library` }

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    api(project(":contract"))
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

// One fixture set owned by:contract, read here and by:payload so both implementations are proven identical.
// The golden rules.json comes too, so that RulesJson reads the same document the contract tests check.
// Sync, not Copy: a fixture deleted in :contract must not stay in the test runtime.
val verdictFixturesDir = layout.buildDirectory.dir("verdict-fixtures")
val verdictFixtures =
    tasks.register<Sync>("verdictFixtures") {
        group = "verification"
        description = "Copies the shared verdict fixtures and the golden rules.json from :contract into the test runtime."
        from(rootProject.file("contract/src/test/resources")) {
            include("verdicts/**")
            include("golden/rules.json")
        }
        into(verdictFixturesDir)
    }
sourceSets["test"].resources.srcDir(verdictFixturesDir)
tasks.named("processTestResources") { dependsOn(verdictFixtures) }

// ScenarioActionsTest reads testdata/scenarios.json. Declare it as an input so that a scenario change runs the
// test again, and give the path so that the test does not depend on the working directory.
val scenariosFile = rootProject.file("testdata/scenarios.json")
tasks.test {
    inputs.file(scenariosFile).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("nj.scenarios", scenariosFile.absolutePath)
}
