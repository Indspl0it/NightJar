// Shared contract: constants, enums, masking, hashing. Compiled into payload.dex and the app.
// No runtime dependencies. org.json comes from the Android framework at runtime.
plugins { `java-library` }

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
