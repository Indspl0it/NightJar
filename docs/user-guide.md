# NightJar user guide

NightJar shows what happens to SMS messages inside the Android phone process. It can block or hold the message types
that you do not want. The network never sees a failed delivery.

NightJar is not a messaging app. Your SMS app still receives normal messages. NightJar watches the same pipeline and
keeps its own record.

## Requirements

- Android 15 or newer (API 35 or higher), arm64-v8a.
- Root (Magisk, KernelSU or APatch) with Vector or LSPosed installed. Vector and LSPosed need a Zygisk
  implementation, for example Magisk with Zygisk turned on, or ZygiskNext.
- The NightJar APK. The APK is the app and the Xposed module.
- The NightJar module turned on in Vector or LSPosed, with the scope Phone Services (`com.android.phone`).

NightJar has no internet permission. It does not read your SMS database. It does not need to be your default SMS app.

## Install

1. Install the NightJar APK.
2. Open Vector or LSPosed. Turn on the NightJar module.
3. Select Phone Services (`com.android.phone`) as the module scope.
4. Reboot the phone.
5. Open the NightJar app. The setup screens check root, the Xposed framework, the module and the phone service. Each
   check that fails tells you what to do.
6. Keep the app installed. Vector or LSPosed loads the module into `com.android.phone` each time the phone service
   starts. The app stores and shows the events.

An update of the APK keeps your rules, settings and recorded events.

## Screens

- **Home**: the protection state, the last events and a short summary of what NightJar did.
- **Messages**: every message that NightJar saw, with its type and its result. Types include normal, flash (class 0),
  silent, WAP push, SIM data download, cell broadcast and sent messages. Results include shown, delivered to an app,
  used by voicemail or a carrier app, blocked, on hold and incomplete.
- **Detail**: the explanation of one message, the decoded PDU fields, the hook trace and the actions for that message
  (create a rule, release a held message, export, delete).
- **Protection**: the blocking switch, the preset and one action for each message type (Allow, Hold or Block). The
  bell adds a notification where the type allows it. This screen also opens Custom rules and Known senders.
- **Custom rules**: ordered rules on type, direction, transport, sender, port and text. The rule editor refuses a rule
  that blocks every normal message.
- **Protection status**: hook health, counters, the safe mode state, the module version and the last self-test.
- **What NightJar cannot see**: the blind spots for your phone and configuration.
- **Settings**: Appearance, Notifications, Protection, Data and storage, Diagnostics and About.
- **Logs** (researcher mode): masked technical logs. Copy them for a bug report.

## Blocking cautions

- **A block is not a delivery failure.** NightJar removes a message after Android acknowledged it to the network. The
  sender still sees "delivered". This is intentional. A failed acknowledgement makes the network send the message
  again and shows the sender that a filter is active.
- **Hold is not available for every type.** NightJar cannot release a message that Android already used, for example
  a SIM data download, a voicemail indication or some WAP pushes. For these types, Protection shows only the actions
  that the type allows.
- **NightJar never blocks emergency alerts.** ETWS and CMAS cell broadcasts, and SUPL messages during an emergency
  call, always go to Android.
- **A block at the first filter also stops later filters.** When a rule blocks a message before the voicemail or
  carrier filter, these apps do not see the message.
- **Do not block your bank, delivery or two-factor sender** unless you have a different way to receive the code.

## Safe mode and troubleshooting

- **After a system update**: NightJar shows "Check again after the update". Run a self-test. Blocking stays on. If a
  hook does not attach on the new build, Protection status shows that check as not OK. NightJar then cannot hold or
  block some message types until a NightJar update supports the build.
- **Safe mode**: safe mode pauses blocking. NightJar continues to record messages. Turn safe mode on or off in
  Settings or on Home. NightJar never turns on safe mode by itself.
- **"NightJar is not active"**: open Vector or LSPosed. Make sure that the NightJar module is on and that its scope
  includes Phone Services (`com.android.phone`). Reboot once after you change the module or its scope.
- **Messages stays empty**: the module sends events to the app through a local binder. When the app is stopped, the
  module keeps the events and tries again later. Open the app and look at Protection status.
- **The phone crashes after the install**: open Vector or LSPosed and turn off the NightJar module. Reboot. Report
  the build fingerprint and a diagnostics bundle in an issue.

## Privacy

- Logs, exports and diagnostics never show message text or full phone numbers. NightJar masks them.
- NightJar keeps message bodies only in the app's encrypted database. An export starts only when you ask for it, and
  it hides text and numbers by default.
- NightJar has no internet permission. Cloud backup is off. A device-to-device transfer can copy the encrypted
  database, so a move to a new rooted phone keeps your history.
