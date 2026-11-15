# NightJar copy standard

This standard defines the language of every string NightJar shows or writes: app text, notifications, dialogs,
explanations, diagnostics and the documentation. It is based on Simplified Technical English (ASD-STE100). It is not
a certified STE document. It does not include the official ASD dictionary. The glossary in section 5 replaces it for
NightJar.

## 1. Modes

| Mode | Applies to | Rules |
|---|---|---|
| Strict | Status, errors, dialogs, button labels, notifications, class explanations, diagnostics | Sections 2, 3, 4 and 5 in full |
| STE-flavored | README, docs, changelog, store text | Section 2 in full. Section 5 is advisory except the glossary terms. |

## 2. Sentence rules

1. Use active voice. Name the actor: "NightJar blocked the message." Not "The message was blocked."
   Use passive voice only if the actor is unknown or does not matter to the reader.
2. One instruction per sentence. Maximum 20 words for an instruction. Maximum 25 words for a description.
3. Use these tenses only: imperative, simple present, simple past, simple future. Keep a compound tense only if
   the simple tense loses meaning. "The module may have failed" keeps "may have".
4. No semicolons. Use two sentences.
5. No phrasal verbs ("turn off" is allowed only as the fixed UI term in the glossary, see section 5).
6. Use a verb, not a noun made from a verb: "Check the log." Not "Perform a check of the log."
7. No noun cluster of more than 3 words.
8. Do not drop words. Keep the subject, the verb and the articles.
9. No contractions: write "cannot", "does not", "is not". Reason: translation tools and non-native readers.
10. No idioms and no figures of speech. Remove: "All quiet", "the trick crash attacks use", "Pops up",
    "stays on this phone" (say "NightJar does not send it off this phone"), "steps aside".
11. No marketing words: seamless, powerful, robust, smart, easy, simple (except the fixed term "Simple mode").
12. No exclamation marks. No emoji. No "please". No "we". The actor is "NightJar", "the phone", "Android" or "you".
13. Keep the strength of every hedge. If the modem may handle a message without telling Android, write "may".
    Do not change "may" to a fact, and do not add a fact the code does not prove.

## 3. Message structure

Every error, warning and status message has up to three parts. Each part is one sentence.

1. **What happened.** A fact. "NightJar cannot block messages on this build."
2. **Why.** Only if known. "The hook HK-03 was not found."
3. **What to do.** An imperative. "Send diagnostics."

Rules:
- Put the most important fact first.
- If there is no action for the user, write no action. Do not write "Try again later."
- Safety-relevant text starts with the command or the condition.
- Never write message text or a full phone number in any string, log or fixture. Use the contract masker.

### Size limits

| Element | Limit |
|---|---|
| Button | 1 to 3 words. Starts with a verb. No full stop. |
| Title, label, tab | 1 to 5 words. A noun phrase. No full stop. |
| Notification title | 5 words or fewer |
| Notification body | 1 sentence, 20 words or fewer |
| Dialog title | A question or a noun phrase, 6 words or fewer |
| Dialog body | 3 sentences or fewer |
| Explanation (class detail) | 3 sentences or fewer |
| Paragraph (docs) | 6 sentences or fewer, one topic |

Use a numbered list for 3 or more steps. Use a bulleted list for 3 or more conditions.

## 4. Placeholders and formatting

- Keep the existing two forms. `{name}` is for the Explainer. `%1$s` and `%1$d` are for UI chrome. Never mix them in
  one string.
- Put a placeholder where a noun would be. Do not build a sentence that works only for one value of the placeholder.
  Plural text uses `plurals.xml`.
- Give every placeholder a comment that states its type and an example, as `strings.xml` does today.
- Write numbers as digits. Write dates and times with the platform formatter.
- Write the product name as "NightJar". Write SIM as "SIM 1", "SIM 2".

## 5. Glossary

One word, one meaning. The "Use" column is the only term for that idea. The "Do not use" column lists the terms to
replace. Change a term here first, then change the strings.

### Actions

| Idea | Use | Do not use |
|---|---|---|
| NightJar stops a message from reaching the messaging app | **block** | drop, discard, kill, stop, filter out |
| NightJar keeps a message and does not deliver it, until the user decides | **hold** | quarantine, park, stash |
| Deliver a held message | **release** | restore, let through, deliver (as a user action) |
| Erase data permanently | **delete** | remove, clear, wipe, erase |
| Take a rule out of a list | **delete** (a rule is data) | remove |
| Record a message in the log | **record** | log (as a verb), save, store |
| Look at the state of something | **check** | verify, confirm, validate |
| Tell the user | **notify** | alert (as a verb), ping, warn |
| Start or end a feature | **turn on**, **turn off** (fixed UI terms) | enable, disable, switch, toggle |
| Send the diagnostics file to the user's chosen app | **share** | export (button), send out |
| Keep for a period | **keep** | retain, preserve |
| Mark a message as seen by the user | **mark as reviewed** | acknowledge, dismiss, clear |

### Nouns

| Idea | Use | Do not use |
|---|---|---|
| An SMS | **message** | text, SMS (except in a class name), txt |
| A message that the messaging app never shows | **hidden message** | stealth, silent text, ghost |
| The software that sends and receives calls and SMS | **phone service** | radio layer, RIL, telephony process (in user text) |
| The part of NightJar that runs in the phone service | **module** | payload, hook layer, agent (in user text) |
| An interception point | **hook** (Researcher mode only) | probe, tap, intercept |
| What the user sets for a message class | **action** (Allow, Hold, Block) | policy, verdict, fate, disposition (in user text) |
| A user-written condition | **rule** | filter, firewall rule, pattern |
| The part of NightJar that blocks | **blocking** | firewall (in user text) |
| The messaging app the user reads messages in | **messaging app** | SMS app, default app, client |
| Where the user sees status | **Protection status** | dashboard, health (in titles) |
| Debug information | **diagnostics** | debug data, telemetry, dump |
| A message class | **type** | class, category, kind (in user text) |

`class`, `verdict`, `fate` and `payload` are valid developer terms in code, logs and Researcher mode only.

### Fixed names

Keep these as proper names: NightJar, Vector, LSPosed, Android, Google Messages, RCS, SIM, SMS, MMS, WAP, OTA,
IMS. Spell out an abbreviation on first use in the docs.

## 6. Technical output (logs and diagnostics)

Machine-read text is not prose. Use this form:

```
<LEVEL> <component> <event> key=value key=value
```

- `LEVEL` is one of `ERROR`, `WARN`, `INFO`, `DEBUG`. Nothing else.
- `event` is a fixed lowercase word group with `_`, for example `hook_not_found`. Do not write a sentence.
- Values are masked by the contract masker. A free-text value is not allowed.
- One event per line. One meaning per key across the whole project. Keys come from `:contract`.

## 7. Review procedure

1. Extract the strings and run an STE linter, for example `ste-lint.py` from
   [asd-ste100-skill](https://github.com/danyuchn/asd-ste100-skill). Zero hard violations is the target.
2. Search the strings for every "Do not use" term in section 5 and for contractions (`n\'t`, `\'s`, `\'re`).
3. A person reads each changed string aloud against the rules in sections 2 and 3. The linter cannot find idioms,
   register or meaning changes.
4. If a shorter text loses a condition, a number or a hedge, keep the longer text.
5. Add each new term to section 5 in the same change.

## 8. Before and after

| Current | Rewritten | Rule |
|---|---|---|
| Delivered privately to an app | Sent to an app. Not shown in your messaging app. | 10, 2 |
| All quiet. Nothing suspicious in the last 24 hours. | No suspicious messages in the last 24 hours. | 10 |
| Corrupt header, the trick crash attacks use | Damaged header. Attackers use it to crash a phone. | 10, 7 |
| Pops up on screen and is never saved | Shows on the screen. Android does not save it. | 10 |
| Couldn't release: {reason}. It's still on hold. | NightJar cannot release the message: {reason}. The message is still on hold. | 9, 1, 3 |
| Blocking isn't available here ({hook}). Messages are still recorded. | NightJar cannot block messages on this build. NightJar still records them. | 9, 1, 2 |
| Delete all data? | Delete all NightJar data? | 8 |
| Re-check after the update | Check again after the update | 5 |
