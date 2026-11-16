---
title: Release
description: How to make a signed NightJar release.
---

## Version

`version.properties` holds one version for the app, the module and the payload:

```properties
versionName=0.5.0
versionCode=5
```

Increase `versionCode` by one for each release. `versionName` follows [Semantic Versioning](https://semver.org/).

## Signing material

The release key is never in the repository. The build reads it from these sources:

| Value | Source |
|---|---|
| Keystore path | `NJ_KEYSTORE`, or `nj.keystore` in `local.properties` |
| Key alias | `NJ_KEY_ALIAS`, or `nj.keyAlias` in `local.properties` |
| Keystore password | `NJ_KEYSTORE_PASSWORD` |
| Key password | `NJ_KEY_PASSWORD` |

The passwords come only from environment variables, so no secret goes into a file. Git ignores `local.properties`,
`*.keystore` and `*.jks`.

If a value or the keystore file is missing, the release APK stays unsigned. When you set the keystore path or the key
alias but not everything else, Gradle prints a warning that names what is missing:

```text
Release signing material is not complete. The release APK stays unsigned. Missing: NJ_KEY_PASSWORD
```

The warning never shows a value, and a release build never uses the debug key.

## What the release build does

- R8 shrinks and optimizes the code (`isMinifyEnabled`) and removes unused resources (`isShrinkResources`).
- The APK contains native libraries for `arm64-v8a` only and resources for English only.
- The APK file name is `NightJar-<versionName>`, for example `NightJar-0.5.0`.

## Procedure

1. Update `version.properties`.
2. Add a section for the new version to `CHANGELOG.md`.
3. Run all quality checks:

   ```sh
   ./gradlew verify
   ```

4. Set the signing variables, and then build the release APK:

   ```sh
   ./gradlew :app:assembleRelease
   ```

5. Find the signed APK in `app/build/outputs/apk/release/`.
6. Check the signature and the certificate:

   ```sh
   apksigner verify --print-certs app/build/outputs/apk/release/NightJar-0.5.0-release.apk
   ```

7. Make an annotated tag, for example `v0.5.0`, and push the tag.
8. Make a GitHub release for the tag. Attach the APK, and copy the changelog section into the release notes.

## Checks on the release APK

`verify` includes these checks for the release variant:

| Task | Fails when |
|---|---|
| `checkManifestRelease` | The manifest declares `INTERNET` or another permission that isn't allowed, cloud backup isn't excluded, or a NightJar provider isn't direct-boot aware. |
| `checkXposedMetadataRelease` | The Xposed meta-data or the `com.android.phone` scope is missing, or `assets/xposed_init` is missing or names a class that R8 removed. |
| `checkApkSizeRelease` | The APK is larger than 15 MiB. |

Before you publish, install the APK on a test phone and run a [self-test](/NightJar/guide/self-test/).
