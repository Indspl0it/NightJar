---
title: Cell broadcasts
description: The cell broadcasts NightJar records, the emergency alert limit and the cell broadcast settings.
---

A cell broadcast goes from the network to every phone in an area. Emergency alerts are cell broadcasts. Networks
also use them for area information and to send data to the SIM.

## Broadcast types

| Broadcast | Example titles in NightJar | Default severity |
|---|---|---|
| Emergency alert (CMAS) | Emergency alert: presidential, extreme, severe, amber, public safety, test, exercise | Normal |
| Earthquake and tsunami warning (ETWS) | Earthquake warning, Tsunami warning, Test warning | Normal |
| Area information | Area information (channel 50) | Normal |
| Other channel | Cell broadcast (channel …) | Unusual |
| Cell broadcast for the SIM | Cell broadcast for the SIM | Suspicious |

NightJar sorts a broadcast by its message identifier, also called the channel:

| Channels | Type |
|---|---|
| 0 to 999 | Channels your operator assigns. Channel 50 is area information, such as the cell or area name. |
| 4096 to 4351 | Data download to the SIM. The phone passes these broadcasts to the SIM, not to you. |
| 4352 to 4359 | ETWS: earthquake, tsunami, both, test and other emergency types |
| 4370 to 4399 | CMAS and EU-Alert: presidential, extreme, severe and AMBER alerts, the same alerts in an additional language, public safety and tests |
| 4360 to 4369, 4400 to 6399 | Other public warning channels. NightJar shows them as generic emergency warnings. |
| Other | A regular channel |

A SIM app can use a cell broadcast for the SIM to change settings on the SIM. NightJar shows these broadcasts as a
separate type, **Cell broadcast for the SIM**. Recommended, Strict and Quarantine block them.

## Emergency alerts

NightJar records emergency alerts and always delivers them. This covers the whole public warning range, channels
4352 to 6399, including channels that have no assigned use yet. No preset or rule can hold or block one, and a rule
that would block an emergency alert has no effect.

On the Protection tab, emergency alerts have a row with no action buttons.

## Settings

Open Settings > Cell broadcasts.

| Setting | Default | Result |
|---|---|---|
| Record broadcasts | On | NightJar records each cell broadcast the phone receives. |
| Receive all channels | Off | Researcher mode only. NightJar asks the modem for all broadcast channels (0 to 65535) instead of only the channels Android selects. |

To get a notification with a copy of each cell broadcast, turn on Settings > Notifications > **Broadcast copies**.

:::note
**Receive all channels** changes the modem's channel list, so the phone can receive more broadcasts than usual.
Turn it off when you don't need it.
:::
