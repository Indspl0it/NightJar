# Release checklist

Use this list for each release tag.

## 1. Code and checks

- [ ] `git status` shows a clean `main`.
- [ ] `./gradlew verify` passes.
- [ ] No `TODO` or `FIXME` remains without an issue link.
- [ ] Each payload change since the last tag follows the payload rules in [architecture.md](architecture.md).

## 2. Device tests

- [ ] The self-test passes on each reference device. No self-test message reaches the default SMS app.
- [ ] Safe mode works. The switch in Settings or on Home turns safe mode on. NightJar stops blocking, and Home shows
      that blocking is paused. The switch turns safe mode off, and blocking starts again.
- [ ] After a fingerprint change (system update), NightJar shows "Check again after the update" and recovers.
      Blocking stays on.

## 3. Versions and files

- [ ] `version.properties` has the new `versionName` and a higher `versionCode`.
- [ ] The contract versions did not change, or they changed with new fixtures on both sides.
- [ ] `app/src/main/resources/nightjar-profiles.json` has an entry for each supported build.
- [ ] The release APK is signed with the release key. `apksigner verify --print-certs` shows the expected
      certificate.
- [ ] A `SHA256SUMS` file lists the APK, and the checksum matches after the upload.

## 4. Licences and notices

- [ ] `NOTICE` lists each third-party component in the APK and its licence.
- [ ] The About screen in the app lists the same components.
- [ ] `LICENSE` is Apache-2.0 and did not change.

## 5. Repository hygiene

- [ ] A secret scan over the history finds no keystore, token or password.
- [ ] Tests, fixtures, docs and commit messages contain no real phone number and no real message text.

## 6. Publication

- [ ] `CHANGELOG.md` has a section for this version.
- [ ] The tag `v<version>` points to the release commit.
- [ ] The GitHub release has the APK, `SHA256SUMS` and `NOTICE` attached.
- [ ] The release notes link the user guide and state what NightJar cannot see.
