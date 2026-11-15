# Security policy

## Report a vulnerability

Do not open a public issue for a security problem. Use the private vulnerability reporting of GitHub on this
repository (Security > Advisories > "Report a vulnerability").

Include:

- the NightJar version (Settings > About),
- the build fingerprint (`adb shell getprop ro.build.fingerprint`),
- the root manager, the Zygisk implementation and the Xposed framework (Vector or LSPosed) with its version,
- the impact: what an attacker gets, and what the attacker needs (a malformed SMS, a malicious app, physical access),
- a minimal reproduction if you have one. Do not include real message text or full phone numbers.

You get an acknowledgement in 7 days and a decision in 30 days. Give us time to release a fix before you publish
the problem.

## Scope

In scope:

- the payload that runs in `com.android.phone` (`payload/`) and the Xposed entry (`app/.../xposed/`),
- the app (`app/`), including its exported provider and its notification actions,
- the rule engine (`rules/`) and the contract (`contract/`),
- the parsers and decoders (`pduparse/`, `decoder/`).

Out of scope:

- the device firmware and the Android telephony stack (report these to the vendor or to Google),
- denial of service that needs root,
- findings that occur only with a modified APK or a debug build,
- missing hardening without a demonstrated impact.

## Design commitments

- The app has no `INTERNET` permission. The payload has no network code.
- The payload runs inside `com.android.phone` and must never throw into it. Every hook fails open.
- Logs, exports and diagnostics mask message text and full phone numbers.
- The app's provider accepts only the phone process UID. The control binder accepts only the app UID.
- A release build is signed with the release key. A release build never uses the debug key.
