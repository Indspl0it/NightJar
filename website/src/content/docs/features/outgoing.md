---
title: Outgoing messages
description: The messages apps and the SIM send from your phone, and how to block an app from sending.
---

Apps on your phone can send SMS without going through your messaging app, and the SIM can send SMS too. When
**Outgoing monitor** is on, NightJar records these messages.

## Message types

| Type | Meaning | Default severity |
|---|---|---|
| Message sent by an app | An app sent a message without your messaging app. Android saved it in your messages. | Suspicious |
| Hidden message sent by an app | An app sent a message without your messaging app. Android didn't save it. | Suspicious |
| Data message sent | An app sent a data message to a port. Data messages never appear in your messaging app. | Suspicious |
| Raw message sent | An app sent a raw SMS packet instead of using the normal message API. | Suspicious |
| The SIM sent a message | The SIM asked the phone to send a message that you didn't write. The Simjacker attack uses this path. | High |
| Voicemail setup message | The phone sent a voicemail setup message to your carrier. | Normal |

NightJar doesn't record messages you send from your default messaging app. To record them too, turn on **Also record
visible messages**.

For each message, NightJar shows the app that sent it, the destination and the result: sent, not sent, or sent
without a confirmation.

## Detectors

These detectors check data, raw, hidden and SIM-sent messages. When one fires, it sets the severity and adds a
sentence to the message detail.

| Detector | Trigger | Severity | What you see |
|---|---|---|---|
| Short code | The destination has 6 digits or fewer | Suspicious | "The destination is a short code, which can carry a charge." |
| Premium number | The destination matches NightJar's list of charged short codes and premium-rate numbers | Suspicious | "The destination matches a charged short code or a premium-rate number." |
| First send | The app sends a message for the first time on this phone | Suspicious | "This app has never sent a message on this phone before." |
| SIM sends after an update | The SIM sends a message within 10 minutes after a SIM update arrived | High | "The SIM sent a message after it received an update. This is the Simjacker pattern." |

A message the SIM sends has no app, so the first send detector checks whether the phone sent to that destination
before.

## Block an app from sending

<ol>
<li>Open Settings > Outgoing messages > <strong>Apps that send messages</strong>.</li>
<li>Find the app in the list.</li>
<li>Turn on the switch for the app.</li>
<li>Select <strong>Block</strong> in the dialog.</li>
</ol>

NightJar then blocks each message the app sends, and the app may show a send error.

The block works only when:

- Outgoing monitor is on.
- Safe mode is off.
- Blocking is on.

:::caution
A message to an emergency number goes out even from a blocked app. A raw message has no destination NightJar can
check, so this exception doesn't cover it. If you block your default messaging app, you can't send messages from it.
:::

## Trusted apps

A trusted app is one you expect to send or add messages in the background. NightJar records its background messages
as Normal and doesn't notify you about them.

To trust an app, open the message detail and select **Mark app as trusted**. You can also open Settings > Outgoing
messages > **Trusted apps** and select **Add apps**.

## Settings

Open Settings > Outgoing messages.

| Setting | Default |
|---|---|
| Outgoing monitor | On |
| Also record visible messages | Off |
| Trusted apps | No apps |
| Apps that send messages | No app blocked |
