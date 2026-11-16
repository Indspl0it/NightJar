---
title: Changelog
description: The changes in each NightJar release.
---

The format follows [Keep a Changelog](https://keepachangelog.com/). The project uses
[Semantic Versioning](https://semver.org/). The file `CHANGELOG.md` in the repository is the source.

## 0.5.0 (2026-11-15)

First public release.

### Added

- Xposed module for Vector and LSPosed with the scope `com.android.phone`. The APK is the module and the app.
- Recording of SMS over CS and IMS, WAP push, SIM data download, cell broadcast, voicemail indications, sent messages
  and messages that apps add. NightJar records the result of each message: shown, given to an app, blocked or held.
- Firewall with one action for each message type, presets, custom rules, known senders and trusted apps. You can
  release held messages.
- Safe mode, which stops all blocking. Only you can turn it on or off.
- Decoders for GSM 03.40 TPDUs, UDH, GSM 7-bit alphabets, UCS-2, WSP, WBXML (SI, SL, CO), OMA CP and DM, SUPL,
  SMS-PP and cell broadcast.
- App: encrypted database, classification, severity, detectors, notifications, Home, Messages, message detail,
  Protection, Protection status, blind spots, settings, export, logs and audit history.
- Self-test with a test message for each type. The test messages never reach the default SMS app.

### Security

- No `INTERNET` permission. Cloud backup is off.
- Masking of message text and phone numbers in logs, exports and diagnostics.
- The app provider accepts calls only from the phone process. The control binder accepts calls only from the app.

[Release 0.5.0 on GitHub](https://github.com/Indspl0it/NightJar/releases/tag/v0.5.0)
