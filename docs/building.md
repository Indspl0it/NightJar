# Building NightJar

## Toolchain

- JDK 21.
- Android SDK with the platforms `android-36` and `android-37`, and Build-Tools 36 or newer.
- The Gradle wrapper downloads Gradle itself. You do not need a local Gradle installation.

The build reads the tool locations from environment variables. It does not contain fixed paths.

| Variable | Use |
|---|---|
| `JAVA_HOME` | The JDK 21 folder. `gradlew` uses this JDK. |
| `ANDROID_HOME` | The Android SDK folder. As an alternative, write `sdk.dir=<path>` in `local.properties` at the repository root. Git ignores `local.properties`. |
| `ROBOLECTRIC_MAVEN_REPO` | Optional. The folder where Robolectric keeps its Android jars. The default is `~/.m2/repository`. |

## Commands

Run all commands from the repository root. On Windows, use `gradlew.bat` instead of `./gradlew`.

```sh
./gradlew :app:assembleDebug        # debug APK
./gradlew test                      # all JVM unit tests
./gradlew spotlessApply             # format the code
./gradlew verify                    # all quality checks (run this before a pull request)
```

The APKs go to `app/build/outputs/apk/`. The APK is the app and the Xposed module.

## Quality checks

`./gradlew verify` runs these tasks. CI runs the same tasks on every push and pull request.

| Check | Tasks |
|---|---|
| Format and static analysis | `spotlessCheck`, `detekt`, `:app:lintDebug`, `checkContractLiterals` |
| Unit tests | `test` in every module |
| Payload dex | `:payload:checkPayloadDex`: framework classes only, 300 KiB or less |
| Manifest | `:app:checkManifestDebug`, `:app:checkManifestRelease`: no `INTERNET`, no extra permissions |
| Xposed metadata | `:app:checkXposedMetadataRelease`: module meta-data, entry class and scope |
| Log masking | `checkLogMasking`: no raw message text or phone number in a log call |
| APK size | `:app:checkApkSizeRelease`: release APK 15 MB or less |

`checkLogMasking` accepts a log call with the comment `// mask-ok: <reason>` on one of its lines. Use it only when the
value cannot hold message text or a phone number.

## Screenshot tests

The JVM screenshot tests use Roborazzi on Robolectric. The `test` task runs them, but does not write or compare
images. To record the reference images:

```sh
./gradlew :app:recordRoborazziDebug
```

## Release build

A release APK is signed only when you supply all of the signing material. Without it, the release APK stays unsigned.
It is never signed with the debug key.

| Item | Environment variable | `local.properties` key |
|---|---|---|
| Keystore file | `NJ_KEYSTORE` | `nj.keystore` |
| Key alias | `NJ_KEY_ALIAS` | `nj.keyAlias` |
| Keystore password | `NJ_KEYSTORE_PASSWORD` | (environment only) |
| Key password | `NJ_KEY_PASSWORD` | (environment only) |

```sh
./gradlew :app:assembleRelease
```

The output is `app/build/outputs/apk/release/NightJar-<version>-release.apk`. Without signing material, the file name
ends in `-release-unsigned.apk`. The version comes from `version.properties`. See [release.md](release.md) for the release checklist.
