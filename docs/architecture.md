# Architecture

NightJar has two halves that run in different processes and talk over local IPC:

- The **payload** runs inside `com.android.phone`. Vector or LSPosed load it as an Xposed module. It hooks the SMS
  pipeline, records each message, applies the firewall verdict and sends events to the app.
- The **app** stores the events in an encrypted database, explains them, shows notifications and pushes the rules and
  settings back to the payload.

The APK holds both halves. Vector or LSPosed read the `xposedmodule` meta-data in the manifest and call
`dev.nightjar.app.xposed.XposedEntry`, which `assets/xposed_init` lists. The module scope is `com.android.phone`. The
module uses the legacy Xposed API 82 through compile-only stubs.

## Modules

| Module | Language | Content |
|---|---|---|
| `contract` | Java | Wire names, IPC keys, `ClassId` and the other wire enums. The single source for both sides. |
| `rules` | Java | Rule engine: compile, evaluate, validate, presets and the enforcement matrix. |
| `pduparse` | Java | PDU, UDH, WSP, WBXML and OTA parser. It never throws. |
| `payload` | Java 17 | Code that runs in `com.android.phone`. Framework APIs only. |
| `decoder` | Kotlin | PDU, WSP, WBXML and cell broadcast decoders for the app (pure JVM). |
| `app` | Kotlin | Compose UI, domain logic, Room database, IPC endpoints and the Xposed entry. |
| `xposed-stubs` | Java | Compile-only stubs of the legacy Xposed API. |
| `hiddenapi-stubs` | Java | Compile-only stubs of internal telephony classes. |
| `buildSrc` | Kotlin | Custom Gradle tasks for the quality checks. |

`testdata/scenarios.json` is a catalogue of synthetic message scenarios. Unit tests in `payload`, `rules`,
`pduparse` and `app` read it. The Python scripts in `testdata/gen/` generate and check it.

## Payload rules

The payload runs inside the phone process. A fault there can stop telephony. Every change must keep these rules:

- Every hook callback runs inside the hook catch-all. A hook never throws into the phone process.
- No I/O, binder call, lock or file logging on the main looper or on HAL threads.
- A hook never changes the network acknowledgement.
- A missing rules snapshot means ALLOW.
- The SMS filter returns true only after `dropFilteredSms`.
- When anything fails, the hook calls the original method unchanged (fail-open).

## Hooks

`payload/src/main/java/dev/nightjar/payload/hook/` holds the hook framework. `HookCatalog` has one `HookSpec` for
each hook: ID (`HK-xx`), target class and method, descriptor, tier, direction and the behaviour when the target is
not found. `Hooker` wraps each callback in the catch-all. `HookRegistry` tracks the health of each hook.

To add a hook:

1. Add a `HookSpec` to `HookCatalog` with its tier and its not-found behaviour.
2. Resolve an obfuscated class by its structure. Never hard-code a `$N` index.
3. Put the body inside the hook catch-all.
4. Decide the fail-open result. Usually this is "call the original method unchanged".
5. Add a unit test with a fake `Member` or `Method`. The test must prove that the original result does not change when
   the hook throws (`FaultInjector` makes a hook throw in tests).
6. Update the hook-health expectations and `nightjar-profiles.json` when a build needs it.

The payload logs to logcat with the tag `Nightjar`. `XposedEntry` writes to the Vector or LSPosed module log with the
prefix `Nightjar:`. Each log line has the hook ID.

## Rule engine

`rules/src/main/java/dev/nightjar/rules/`:

- `RulesJson` reads and writes the rules document with `org.json`. It ignores unknown fields. An unknown enum value is
  dropped (class actions) or makes the rule inactive (conditions).
- `RuleEngine.compile` makes a frozen snapshot: an `EnumMap<ClassId, Verdict>` for class actions, the rules in
  priority order, a number trie for senders and `SafeRegex` (a linear-time Pike VM) for regex conditions.
- `RuleEngine.evaluate` allocates no memory after warm-up and applies the clamps in order. A clamp records
  `clampedFrom` and `clampReason`.
- `RuleValidator` returns typed errors for the rule editor. `Presets` holds the preset data.
- `EnforcementMatrix` is the single source for the actions that each class allows. Do not copy it into the app or the
  payload.

To add a message class, update `ClassId`, `EnforcementMatrix`, `ClassRegistry` (app), the decoder classification and
the fixtures. `:contract:test` and `:rules:test` fail when a wire name or a matrix row is missing.

## IPC

- The payload sends events to the app through `EventProvider`, a content provider that accepts only the phone process
  UID.
- The app pushes rules (`RulesSync`) and settings to the payload. A missing or invalid snapshot means ALLOW.
- The app calls the payload through a hand-written control binder (`PayloadConnection` on the app side,
  `ControlBinder` in the payload). The binder accepts only the app UID. It handles release of held messages, the
  self-test, hook health, logs and the liveness ping.

## Contract changes

`:contract` is shared by Java and Kotlin code. A change there is a protocol change:

1. Increase the affected schema version in `Schemas` (rules, settings, profiles, export, spool) or `Ipc.VERSION`.
2. Change the Java constant and both sides in the same commit.
3. Add or update a golden fixture under `contract/src/test/resources/`. The contract tests must read it back unchanged.
4. Keep contract key literals inside `:contract`. The `checkContractLiterals` task fails otherwise.

## Build profiles

`app/src/main/resources/nightjar-profiles.json` maps a build fingerprint pattern to a hook profile. `XposedEntry` reads
it and gives the result to the payload, which reports it in hook health. The firewall is on by default on every
build. Only the user turns it off.

Add a profile only for a build where the hooks were observed live. Change `schema` only when the file format
changes.
