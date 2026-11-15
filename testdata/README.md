# Scenario catalogue

`scenarios.json` is the single list of synthetic message scenarios for the NightJar unit tests.
The tests in `payload`, `rules`, `pduparse` and `app` read this file. Do not copy scenarios to other files.

## File format

The file is JSON. The top-level keys are:

| Key | Meaning |
|---|---|
| `version` | Format version. The value is 1. |
| `anomalyNames` | Anomaly code and the words that the title uses. |
| `anomalyPolicy` | Lists of anomaly codes that stop the parse (`hard`, `wspHard`) and codes that do not stop it (`soft`). |
| `scenarios` | The list of scenarios. |

## Scenario fields

| Field | Meaning |
|---|---|
| `id` | Unique id in kebab-case. |
| `family` | Group name of the scenario. |
| `title` | Short name of the scenario. |
| `tier` | 1 = an app can send it with the public Android API. 2 = it needs a raw PDU and root. |
| `pdu` | Hex string. It holds the SMSC address and the SMS-DELIVER or SMS-STATUS-REPORT TPDU, as the target receives it. For `method` `cb-pdu`, it holds one 88-byte cell broadcast page. |
| `port`, `payload` | Tier 1 data message. `payload` is hex. |
| `text` | Tier 1 text message. |
| `parts` | Multipart text. Each part has `text` and `pdu`. |
| `sequence` | List of `ref` (scenario id) and `delaySeconds`, for scenarios made of more than one message. |
| `method` | Set when no single received PDU exists: `inject-provider`, `stk-send`, `app-send`, `cb-pdu`, `consumer-observed`. |
| `expect` | `class`, `subtype` (or null), `severity`, `action`, `title`. It can also hold `anomalies`, `reason` and `extraSubtypes`. |
| `expect.action` | The default action of the Recommended preset: `Allow`, `Notify` (allow and tell the user), `Hold` or `Block`. |
| `expect.extraSubtypes` | The rule matches after the first one, in rule order. A code of the same class is the bare subtype code. A code of another class is `<class>:<subtype>`, or `<class>` when that rule has no subtype. A scenario without the key expects no extra subtypes. |
| `spec` | The public specification that defines the PDU. |
| `checkSpec` | `true` when a value must be checked against the specification. |
| `precondition` | State that the target must have before the test. |
| `waitHours` | Time to wait before the result shows. |
| `note` | Extra information about the scenario. |

## Data rules

- Every sender is a synthetic number (555-01xx style).
- Every text is synthetic ("Test message 1").
- SIM OTA scenarios hold dummy counters and dummy data. They hold no key.

## Who reads the file

| Reader | How |
|---|---|
| Classifier test (`:payload:test`) | `ScenarioClassifierTest` classifies every inbound PDU and compares `class`, `subtype`, `anomalies` and `extraSubtypes`. |
| Action test (`:rules:test`) | `ScenarioActionsTest` compares `action` with the Recommended preset. |
| Title test (`:app:test`) | `ScenarioTitleTest` compares `title` and `severity` with the app class registry. |

## Generators

Do not edit `scenarios.json` or the generated files by hand. Change the generator and run it again from the
repository root. Write the intent file to `build/` (git ignores it). Make this folder if it does not exist. The repository does not keep the intent file.

```
python -I testdata/gen/gen_scenarios.py testdata/scenarios.json build/intent.json
python -I testdata/gen/check_scenarios.py testdata/scenarios.json build/intent.json
python -I testdata/gen/gen_verdicts.py contract/src/test/resources/verdicts --check
```

| Script | Input | Output |
|---|---|---|
| `gen_scenarios.py` | The tables in the script | `scenarios.json` and the intent file (the planned field values of each PDU) |
| `check_scenarios.py` | `scenarios.json` and the intent file | Exit code 1 and a list of failures when a check fails |
| `gen_verdicts.py` | The class-action matrix in the script (keep it equal to `EnforcementMatrix`) | The per-class verdict fixtures in `contract/src/test/resources/verdicts/`. With `--check` it writes nothing and gives exit code 1 on a difference. |

The check script parses every PDU again and compares the fields with the planned values in the intent file.
