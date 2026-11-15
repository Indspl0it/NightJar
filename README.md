# NightJar

NightJar shows every SMS that your Android phone receives, including the types that never reach your messaging app:
silent SMS, class-0 flash messages, SIM toolkit and OTA messages, WAP push, voicemail indications and carrier control
messages. It can also block or hold these types before Android acts on them.

NightJar works inside the phone's own SMS pipeline (`com.android.phone`) as an Xposed module for Vector or LSPosed.
The same APK is the module and the companion app. The module records each message and applies your rules. The app
stores, explains and notifies. Google Messages and RCS continue to work as usual.

> **Status:** 0.5.0, early release. Test it on a phone that you can recover. Read the
> [blocking cautions](docs/user-guide.md#blocking-cautions) before you turn on blocking.

## Requirements

- Android 15 or newer (API 35 or higher), arm64.
- Root (Magisk, KernelSU or APatch) with Vector or LSPosed. Vector and LSPosed need a Zygisk implementation, for
  example Magisk with Zygisk turned on, or ZygiskNext.
- The NightJar module turned on in Vector or LSPosed with the scope Phone Services (`com.android.phone`), then one
  reboot.
- GSM, UMTS, LTE and NR SMS, and SMS over IMS. CDMA is not supported.

Reference phones: Pixel 8a (Android 17) and OnePlus 13 (OxygenOS 16). On a build without a profile, NightJar may not
hold or block some message types. If the blocking hooks do not load, NightJar only records messages.

## What NightJar never does

- It has no internet permission. No data leaves the phone.
- It does not store the normal text messages that your messaging app shows.
- It fails open. If an error occurs inside the phone process, NightJar does nothing more with that message, and
  Android handles it as if NightJar were not installed.

## Install

Download the APK from the [releases page](https://github.com/Indspl0it/NightJar/releases), then follow the
[user guide](docs/user-guide.md#install).

## Build

You need JDK 21 and the Android SDK (platforms 36 and 37).

```sh
./gradlew :app:assembleDebug     # debug APK in app/build/outputs/apk/debug/
./gradlew test                   # unit tests
./gradlew verify                 # all quality checks
```

See [docs/building.md](docs/building.md) for the toolchain, the quality checks and release signing.

## Repository layout

| Path | Content |
|---|---|
| `app/` | Companion app (Kotlin, Jetpack Compose) and the Xposed entry point |
| `payload/` | Code that runs inside `com.android.phone`: hooks, capture, firewall, IPC |
| `contract/` | Wire names, IPC keys and message classes shared by the payload and the app |
| `rules/` | Rule engine shared by the payload and the app |
| `pduparse/` | PDU, UDH, WSP, WBXML and OTA parser |
| `decoder/` | PDU and cell broadcast decoders for the app |
| `xposed-stubs/`, `hiddenapi-stubs/` | Compile-only API stubs |
| `buildSrc/` | Gradle tasks for the quality checks |
| `testdata/` | Synthetic message scenarios for the unit tests |
| `docs/` | User guide, architecture, build and release documentation |

## Documentation

- [User guide](docs/user-guide.md)
- [Architecture](docs/architecture.md)
- [Building](docs/building.md)
- [Release checklist](docs/release.md)
- [Copy standard](docs/copy-standard.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## Licence

Apache License 2.0. See [LICENSE](LICENSE). Third-party notices are in [NOTICE](NOTICE).
