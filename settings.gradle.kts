pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.topjohnwu.libsu") } }
    }
}

rootProject.name = "nightjar"

include(
    ":app",
    ":payload",
    ":contract",
    ":rules",
    ":hiddenapi-stubs",
    ":xposed-stubs",
    ":decoder",
    ":pduparse",
)
