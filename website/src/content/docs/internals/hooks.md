---
title: Hooks
description: The hooks that the NightJar module attaches to the SMS path of the phone service.
---

The module hooks methods of the Android telephony framework in `com.android.phone`. The hook catalog is
`payload/src/main/java/dev/nightjar/payload/hook/HookCatalog.java`. Each entry is a `HookSpec` with an ID, a tier, a
kind (`BEFORE`, `AFTER` or `AROUND`, which HK-14 uses), the target class and method, and the way to find the target.

## Startup

Vector or LSPosed calls `XposedEntry` when it loads `com.android.phone`, and `XposedEntry` calls the module's
`Entry.start`. At that point the phone application has no `Context` yet.

1. `Entry.start` resolves and installs the hooks. This step uses reflection only, with no I/O and no binder calls.
2. The tier manager checks the capture tier. If it fails, the module removes all its hooks and reports the build as
   `UNSUPPORTED`. Android then runs as if NightJar weren't installed.
3. When a `Context` exists, the payload worker loads the stored copy of the last rules, settings and Blocking
   switch, and then starts the firewall, the release and self-test injection, the pending-unlock spool, the IPC and
   the head-filter sweep.
4. The session moves from `LOADED` to `HOOKING` to `RUNNING`. Until the first `hello` reply, the module uses the
   stored copy. Without a copy, every type is set to Allow.

`Entry.start` runs inside a catch-all. It returns 0 when the session reaches `RUNNING`, -1 after a caught failure, and
-2 for an unsupported build.

## Finding a target

The `Resolver` tries these methods in order:

1. Class name, method name and parameter types, with the phone application's class loader.
2. An anonymous class, found by its superclass or interface. The resolver checks up to 30 anonymous classes.
3. An overload, found by method name and parameter count.
4. On OnePlus, the class loader of the `mImpl` object.

A method declared on a superclass is found by a scan of the class hierarchy. Because the lookup doesn't need an exact
signature, a signature change between builds doesn't break it.

## Callback rules

`Hooker` runs each hook with these rules:

- If `before` throws, the module counts the error and the original method runs as if no hook existed.
- If `after` throws, the module counts the error and the original result stands.
- If the original method throws, the module throws the same exception again. HK-14 is the one exception: it turns a
  framework parser exception into a result.
- If the original method can't be called, the module disables the hook at once.
- A hook that throws more than 20 times in one session becomes `DISABLED` and is removed. The tier manager then
  checks the tiers again.

Hook callbacks run on the thread that called the hooked method, such as the main looper or a radio HAL binder thread.
They don't do I/O, binder calls, JSON work or lock waits.

## Hook states

| State | Meaning |
|---|---|
| `LIVE` | The hook is installed and runs. |
| `NOT_FOUND` | The resolver didn't find the target on this build. |
| `FAILED` | The target was found, but the install failed. |
| `DISABLED` | The hook threw too often and the module removed it. |
| `NOT_APPLICABLE` | The hook doesn't apply to this build. |

## Tiers

| Tier | Purpose |
|---|---|
| capture-required | Records the incoming message. Without these hooks, NightJar can't record messages. |
| capture-optional | Records more paths or more detail |
| meaning | Records what happened to a message: the filter or app that received it |
| firewall-core | The points where NightJar holds or blocks a message |
| firewall-extra | More points for some types |
| outbound | Records the messages that apps and the SIM send |

The tier manager combines the hooks into four groups:

| Group | Hooks that must be live | If one is missing |
|---|---|---|
| Capture | HK-06, HK-04, and HK-01 or HK-01b | The module removes all hooks and reports `UNSUPPORTED`. |
| Firewall | HK-24, HK-14, HK-15 | The firewall is unavailable. Verdicts are limited to Allow and Notify. |
| Outbound | The HK-19 family and HK-20. HK-19d doesn't count. | The module doesn't record outgoing messages. |
| Meaning | HK-07, HK-09 to HK-12, the HK-13 family | Only the affected results become time-based. Nothing else stops. |

In researcher mode, the [Protection status](/NightJar/guide/protection-status/) screen shows the state of each
hook.

## Incoming messages: radio and IMS

| ID | Tier | Target |
|---|---|---|
| HK-01 | capture-required | `MessagingIndication.newSms` (AIDL radio) |
| HK-01b | capture-optional | `RadioIndication.newSms` (HIDL radio) |
| HK-02, HK-02b | capture-optional | `newSmsStatusReport` (delivery reports) |
| HK-03 | capture-optional | `MessagingIndication.newSmsOnSim` |
| HK-03b | capture-optional | `SIMRecords.handleSms` |
| HK-04 | capture-required | `ImsSmsDispatcher` listener `onSmsReceived` |
| HK-05 | capture-optional | `ImsSmsDispatcher` listener `onSmsStatusReportReceived` |

## Dispatch and enforcement

| ID | Tier | Target |
|---|---|---|
| HK-06 | capture-required | `InboundSmsHandler.dispatchMessage` |
| HK-07 | meaning | `GsmInboundSmsHandler.dispatchMessageRadioSpecific` |
| HK-08 | firewall-core | The NightJar head filter in the `InboundSmsHandler` filter chain |
| HK-24 | firewall-core | Each `InboundSmsHandler` constructor. Installs the head filter. |
| HK-14 | firewall-core | `WapPushOverSms.dispatchWapPdu` (WAP push) |
| HK-15 | firewall-core | `UsimDataDownloadHandler.handleUsimDataDownload` (SIM data download) |
| HK-23 | firewall-extra | `GsmInboundSmsHandler.updateMessageWaitingIndicator` (voicemail indicator) |

The head filter (HK-08) has no hook of its own. HK-24 adds it to the front of the filter chain of each new
`InboundSmsHandler`, for example after a SIM swap. For the handlers that already exist when the module starts, a
startup sweep adds the filter. Both write the chain through `setSmsFiltersForTesting`. The module marks HK-08 as `LIVE`
only when the chain contains the filter.

## Meaning

| ID | Target |
|---|---|
| HK-09 | `VisualVoicemailSmsFilter.filter` |
| HK-10 | `MissedIncomingCallSmsFilter.filter` |
| HK-11 | The carrier services filter callback |
| HK-12 | `AppSmsManager.handleSmsReceivedIntent` (app-only messages) |
| HK-13a | `InboundSmsHandler.dispatchIntent` |
| HK-13b | `InboundSmsHandler.dropFilteredSms` |
| HK-13c | `BlockChecker.getBlockStatus` (the system blocked-numbers list) |
| HK-15b | `UsimDataDownloadHandler.sendSmsAckForEnvelopeResponse` |
| HK-16, HK-26 | `SmsBroadcastUndelivered` (messages that wait for the first unlock) |

## Cell broadcast

| ID | Tier | Target |
|---|---|---|
| HK-17 | capture-optional | The handler of `CellBroadcastServiceManager` |
| HK-18 | firewall-extra | The broadcast channel configuration ("Receive all channels") |
| HK-25 | | The emergency call state, for the SUPL grace time |

## Outgoing messages

| ID | Target |
|---|---|
| HK-19a to HK-19i | The nine `SmsController.send*` entry points |
| HK-20 | `SMSDispatcher.sendRawPdu` |
| HK-21 | `SmsController.injectSmsPduForSubscriber` (messages that an app adds) |
| HK-22 | `CatService.sendStkSms` (messages that the SIM sends) |
| HK-27, HK-27b, HK-27c | The send result: `handleSendComplete`, `SmsTracker.onSent`, `SmsTracker.onFailed` |

## OnePlus

| ID | Target |
|---|---|
| HK-30 | `OplusInboundSmsHandlerImpl.oemCheck` |
| HK-31 | `InboundSmsHandler.romDispatchSmsDeliveryIntent` |
| HK-32 | `OplusSmsBroadcastUndelivered.scanRawTable` |

## Hooks that may not apply

Some hooks don't apply on every build. A missing hook from this list doesn't fail the capture tier:

- HK-01b and HK-02b: a phone with the AIDL radio interface doesn't use the HIDL methods.
- HK-19d: OnePlus has no `sendRawPduForSubscriber`. HK-20 still records raw PDUs.

## Rules for a new hook

Each new hook must:

1. Run its callback inside the module's catch-all.
2. Do no I/O, binder calls, lock waits or file logging on the main looper or on HAL threads.
3. Have a `HookCatalog` entry with a tier and a defined behaviour when the method isn't found.
4. Have a unit test that proves the original result doesn't change when the hook throws.

[Contributing](/NightJar/dev/contributing/) lists the other requirements for a change.
