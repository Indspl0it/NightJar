---
title: IPC
description: How the module in the phone service and the app exchange events, rules and commands.
---

The module runs in `com.android.phone` and the app runs in `dev.nightjar`. They use two channels: a content provider
for events from the module, and a binder for commands from the app. The wire names, keys and limits are in
`contract/src/main/java/dev/nightjar/contract/Ipc.java` and `Control.java`.

```d2 title="IPC channels between the module in the phone service and the app"
grid-rows: 1
grid-columns: 2
horizontal-gap: 170
style.fill: "#0b1020"
classes: {
  proc: {label.near: top-left; grid-columns: 1; vertical-gap: 60; style: {fill: "#0f1830"; stroke: "#34508f"; stroke-dash: 4; font-color: "#9db4ea"; border-radius: 14; bold: true}}
  nj: {width: 250; style: {fill: "#1b2c55"; stroke: "#5b82e6"; font-color: "#eef2ff"; border-radius: 8}}
  gate: {width: 250; style: {fill: "#2a2010"; stroke: "#d29922"; font-color: "#f5e3b8"; border-radius: 8}}
  store: {width: 250; shape: cylinder; style: {fill: "#1b2c55"; stroke: "#5b82e6"; font-color: "#eef2ff"}}
}
(** -> **)[*].style: {stroke: "#8ea8ec"; font-color: "#c9d4f2"}

phone: "Phone service · UID 1001" {
  class: proc
  hooks: "Hook threads\nnever wait" {class: nj}
  queue: "Event queue\nbounded · drops are counted" {class: nj; shape: queue}
  worker: "IPC worker\nbatches · retry" {class: nj}
  pin: "Certificate pin\ncontent only after a match" {class: gate}
  binder: "Control binder\naccepts the app UID only" {class: gate}
  hooks -> queue -> worker -> pin
}

app: "NightJar app · dev.nightjar" {
  class: proc
  grid-rows: 4
  grid-columns: 2
  horizontal-gap: 80
  provider: "IPC provider\naccepts UID 1001 only" {class: gate}
  g1: "" {width: 250; style.opacity: 0}
  ingest: "Event ingest" {class: nj}
  staging: "Staging store\nbefore the first unlock" {class: store}
  db: "Main database\nSQLCipher" {class: store}
  g2: "" {width: 250; style.opacity: 0}
  control: "Rules · settings · release\nself-test" {class: nj}
  provider -> ingest
  ingest -> staging: locked
  ingest -> db: unlocked
  staging -> db: after unlock
}

phone.pin -> app.provider: "hello · events · counters\nhookHealth · log"
app.control -> phone.binder: "control binder calls"
```

## Module to app: events

The module calls the app provider `dev.nightjar.ipc` with `ContentResolver.call`. The provider is exported, because
the phone process must reach it, and it answers `null` to any caller whose UID isn't 1001. The module treats `null` as
a failed call.

| Method | Content |
|---|---|
| `hello` | The first call of a session. It carries the IPC version, the session ID, the module version, the build fingerprint, the APEX versions and the boot count. It also carries the hook health, the count of dropped observations, the control binder and the unlock state. |
| `events` | A batch of events. The app replies with the keys of the events it stored, in the form `eventId:revision`. |
| `counters` | Counters, for example the count of regular texts. At most one call each 60 seconds. |
| `hookHealth` | The state of each hook |
| `log` | Masked log lines of the module, at most 300 lines in each call |

The reply to `hello` carries the current rules snapshot, the settings document and the state of the **Blocking**
switch. The module keeps a copy of the last rules snapshot, settings document and **Blocking** state in its
device-protected directory and loads it at startup, before the first `hello`. Without a copy, every type is set to
Allow until the first `hello` reply, so the module only records.

### Limits

| Limit | Value |
|---|---|
| Events in one batch | 50 |
| Bytes in one batch | 256 KiB of JSON |
| Rules snapshot | 64 KiB |
| Settings document | 16 KiB |
| Parts in one release or replay | 32 |

The module ignores an `updateRules` or `updateSettings` call that is empty or over its limit, and keeps the previous
snapshot.

### Queue and retry

1. A hook thread puts each observation in `EventQueue`, a lock-free queue for 4,096 observations. Hook threads don't
   wait. When the queue is full, the module drops the observation and counts it.
2. The payload worker thread turns the observations into events. When the module knows what happened to a message,
   it seals the event. If nothing reports a result, the module seals the event 30 seconds after the last stage.
3. Sealed events wait in `RingBuffer` until the app acknowledges them. The buffer holds 1,000 events or 4 MB. When it
   is full, the module drops the oldest event and reports the drop with the next batch.
4. The `NightjarIpc` thread sends the events. It waits up to 100 ms to collect a burst, or less if 50 events are
   ready. Each step of this thread makes at most one call, in this order: `hello`, `events`, `counters`,
   `hookHealth`, `log`.
5. The app commits the batch in one database transaction and then replies with the stored keys. The module removes
   only the acknowledged events.

If a call fails, the module waits 1 second before the next try and doubles the wait after each failure, up to
60 seconds. A dead binder, a rejected caller or an `UNKNOWN_SESSION` reply starts a new `hello`. A `LOCKED` or
`STAGING_UNAVAILABLE` reply acknowledges nothing, and the module sends the same events again later. If the app refuses
the same single event three times in a row, the module drops that event.

The app process can restart without the module noticing. When there is nothing to send, the module sends the hook
health every 15 minutes. If the app has restarted, it answers `UNKNOWN_SESSION`, and the module starts a new session.

### Certificate pin

The settings document carries the app package and the SHA-256 hash of its signing certificate. The module compares
the installed package with that hash. Until they match, the module sends only `counters`, `hookHealth` and `log`, and
message content stays in the module. A missing hash counts as a mismatch. This check keeps message content away from
another app that uses the same package name.

### Before the first unlock

Before the first unlock after a reboot, the app can't open its main database, which is in credential-protected
storage.

- The app answers `hello` but sends no rules, settings or **Blocking** state, because it can't read them yet. The
  module keeps using the copy it loaded at startup. After the unlock, the app sends the current rules.
- The provider is direct-boot aware. It writes each batch to a staging database in device-protected storage and then
  acknowledges it. The staging database keeps the raw event JSON, with PDUs and metadata and no decoded text. It holds
  up to 500 events.
- After the unlock, the app moves the staged events to the main database through the same ingest code as live events.
  It deletes a staged row only after its commit.
- In the module, events that wait for the unlock go to `pending_unlock.spool` in the phone process's device-protected
  storage, so they survive a phone service restart. The spool holds up to 500 entries or 2 MiB, and the oldest entry
  goes first.
- The head filter doesn't filter regular SMS before the first unlock. Android keeps those messages in its raw table
  and offers them again after the unlock, and the head filter checks them then. The enforcement points that run
  before the unlock use the stored rules.

## App to module: control binder

The `hello` call gives the app a binder with the descriptor `dev.nightjar.IPayloadControl/1`. The binder accepts calls
only from the app UID that the module recorded during `hello`. Before the first `hello`, it rejects every call.

| Code | Command | Call type | Purpose |
|---|---|---|---|
| 1 | `UPDATE_RULES` | oneway | Sends a new rules snapshot. The module compiles it and swaps it in. |
| 2 | `UPDATE_SETTINGS` | oneway | Sends the module settings |
| 3 | `SET_SAFE_MODE` | oneway | Turns safe mode on or off |
| 4 | `ENABLE_FIREWALL` | oneway | Turns blocking on or off |
| 5 | `RELEASE` | synchronous | Gives a held message back to Android |
| 6 | `RUN_SELF_TEST` | synchronous | Starts a self-test with test messages |
| 7 | `GET_HOOK_HEALTH` | synchronous | Returns the state of each hook |
| 8 | `GET_LOG` | synchronous | Returns the masked log of the module |
| 9 | `PING` | synchronous | Checks that the module answers |
| 10 | `CANCEL_SELF_TEST` | oneway | Stops a running self-test |
| 11 | `REPLAY` | synchronous | Sends a stored message through the local SMS path again |

The module stores the last **Blocking** switch in a one-character file in its device-protected directory, so the
switch survives a reboot. If the file is missing or unreadable, blocking stays on.

### Release and replay

The app keeps the PDUs of each held message. To release it, the app sends the parts in order with the event ID, the
format and the SIM subscription. The module then:

1. Rejects the request if the message type can't be released. This check runs in the module too, so a bad request
   can't release a SIM update or a cell broadcast.
2. Registers a token for each part, so the released message isn't recorded as an injected message or held again.
3. Calls `SmsDispatchersController.injectSmsPdu` on the main looper for each part and waits up to 10 seconds for each
   result. The first failure stops the sequence.

| Result | Code |
|---|---|
| `HANDLED` | 1 |
| `GENERIC_FAILURE` | 2 |
| `OUT_OF_MEMORY` | 3 |
| `INVALID_ARGUMENTS` | -10 |
| `NOT_RELEASABLE` | -11 |
| `TIMEOUT` | -12 |
| `NO_DISPATCHER` | -13 |
| `CALLER_REJECTED` | -14 |
| `NOT_SUPPORTED` | -15 |

If a release fails, the message stays on hold and the app shows an error.

A replay uses the same local injection path, so it never touches the radio. The new record is marked as a replay and
links to the original event. Cell broadcasts, SIM data downloads, status reports and outgoing messages can't be
released or replayed.

## Protocol version

The current IPC version is 2. Both sides use the lower of their two versions. An app that reads version 1 ignores the
new event fields and reads an unknown type as no type.

A change to the `contract` module is a protocol change and needs test fixtures on both sides. The quality check
`checkContractLiterals` fails if a contract name is copied outside `contract`.
