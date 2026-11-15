# Changelog

All notable changes to NightJar are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/)
and the project uses [Semantic Versioning](https://semver.org/).

## [0.5.0] - 2026-11-15

First public release.

### Added

- Xposed module for Vector and LSPosed with the scope `com.android.phone`. The APK is the module and the app.
- Capture of SMS over CS and IMS, WAP push, SIM data download, cell broadcast, voicemail indications (MWI), sent
  messages and messages that apps add, with the result of each message (shown, delivered to an app, blocked, on hold).
- Firewall with one action for each message type (Allow, Hold, Block), presets, custom rules, known senders and
  trusted apps. Held messages can be released.
- Safe mode that pauses blocking. Only the user turns it on or off.
- Decoders for GSM 03.40 TPDUs, UDH, GSM 7-bit alphabets, UCS-2, WSP, WBXML (SI, SL, CO), OMA CP and DM, SUPL, SMS-PP
  and cell broadcast.
- App: encrypted event database, classification, severity, detectors, notifications, Home, Messages, Detail,
  Protection, Protection status, blind spots, settings, export, logs and audit history.
- Self-test with test messages for each class that never reach the default SMS app.

### Security

- No `INTERNET` permission. Cloud backup is off.
- Masking of message text and phone numbers in logs, exports and diagnostics.
- The app provider accepts only the phone process UID. The control binder accepts only the app UID.

[0.5.0]: https://github.com/Indspl0it/NightJar/releases/tag/v0.5.0
