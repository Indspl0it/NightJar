plugins { `kotlin-dsl` }

repositories {
    mavenCentral()
    // com.android.tools:r8 (payload dex shrink step) is published on Google's Maven.
    google()
}

dependencies {
    // The same R8 line the Android Gradle plugin uses, called directly so the payload dex is shrunk without R8's
    // obfuscator or optimizer (payload/payload.pro).
    implementation("com.android.tools:r8:9.5.28")
}
