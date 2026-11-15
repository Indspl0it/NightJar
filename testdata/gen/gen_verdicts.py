"""Mask-aware generator for the per-class verdict fixtures (verdicts/CL-*.json).

Keep MATRIX equal to EnforcementMatrix when a mask changes, then run this script. The other fixture files (clamps,
conditions, ordering, no-enforcement-point) are written by hand.

Each class file has 4 class-action cases (ALLOW, NOTIFY, QUARANTINE, BLOCK) and 4 rule cases (a rule with a sender
condition and each action). CL-NORMAL has 4 more rule cases without a sender/port/text condition. The expected
verdict comes from MATRIX, a mirror of EnforcementMatrix. Output format: json.dumps with
indent 2 and sorted keys, LF, one final newline (same bytes as the hand-made files).

Usage: python -I testdata/gen/gen_verdicts.py contract/src/test/resources/verdicts [--old] [--check]
  --old use the first matrix (classes in NEW ALLOW only, direction IN for every class), to prove the generator
           reproduces the first files
  --check  compare only, write nothing; exit 1 on a difference
"""
import json
import os
import sys

A, N, Q, B = 1, 2, 4, 8
AN, ANB, ALL = A | N, A | N | B, A | N | Q | B
ACTIONS = [("ALLOW", A), ("NOTIFY", N), ("QUARANTINE", Q), ("BLOCK", B)]

# Class-action mask (EnforcementMatrix.classActionMask).
MATRIX = {
    "CL-NORMAL": A, "CL-SILENT": AN, "CL-FLASH": ALL, "CL-REPLACE": ALL, "CL-MWI": ALL,
    "CL-VVM": AN, "CL-MISSED-CALL": AN, "CL-CARRIER": AN, "CL-RCS-PROV": AN, "CL-APP-TOKEN": AN,
    "CL-DATA-PORT": ALL, "CL-WAP-MMS": ALL, "CL-WAP-SISL": ALL, "CL-WAP-CP": ALL, "CL-WAP-DM": ALL,
    "CL-WAP-SUPL": ALL, "CL-WAP-OTHER": ALL, "CL-SIM-DD": ANB, "CL-SIM-STORED": A, "CL-STATUS": A,
    "CL-CB": ANB, "CL-OEM": AN, "CL-INJECTED": ALL,
    "CL-MO-VISIBLE": AN, "CL-MO-UNPERSISTED": AN, "CL-MO-DATA": AN, "CL-MO-RAW": AN, "CL-MO-STK": AN,
    "CL-MO-VVM": AN,
    # Classes added later (NEW).
    "CL-RETURN-CALL": ALL, "CL-ME-DOWNLOAD": ALL, "CL-ME-DEPERSO": ALL, "CL-CLASS3": ALL, "CL-TELEMATIC": ALL,
    "CL-EMPTY": ALL, "CL-BINARY": ALL, "CL-COMPRESSED": ALL, "CL-MULTIPART-INCOMPLETE": AN,
    "CL-MALFORMED-SMS": ANB, "CL-SMART": ALL, "CL-LOC-SUPL": ALL, "CL-WAP-CO": ALL, "CL-WAP-DS": ALL,
    "CL-WAP-EMN": ALL, "CL-WAP-DRM": ALL, "CL-WAP-VCARD": ALL, "CL-WAP-IMPS": ALL, "CL-WAP-UNREADABLE": ANB,
    "CL-CB-SIM": ANB,
    # Wrong message type. AOSP still reads it as an SMS-DELIVER, so a release works (ALL).
    "CL-UNREADABLE-SMS": ALL,
}
# CL-MALFORMED-SMS and CL-WAP-UNREADABLE are not releasable, so no QUARANTINE (ANB).
NEW = ["CL-RETURN-CALL", "CL-ME-DOWNLOAD", "CL-ME-DEPERSO", "CL-CLASS3", "CL-TELEMATIC", "CL-EMPTY", "CL-BINARY",
       "CL-COMPRESSED", "CL-MULTIPART-INCOMPLETE", "CL-MALFORMED-SMS", "CL-SMART", "CL-LOC-SUPL", "CL-WAP-CO",
       "CL-WAP-DS", "CL-WAP-EMN", "CL-WAP-DRM", "CL-WAP-VCARD", "CL-WAP-IMPS", "CL-WAP-UNREADABLE", "CL-CB-SIM"]
# EnforcementMatrix.ruleActionMask(c, true) where it differs from the class mask. The consumer classes
# lost their wider rule mask (no rule for them matches at HK-08); --old keeps ALL for them (OLD_SENDER).
SENDER_RULE = {"CL-NORMAL": ALL}
OLD_SENDER = {"CL-NORMAL", "CL-VVM", "CL-MISSED-CALL", "CL-APP-TOKEN", "CL-CARRIER", "CL-RCS-PROV"}
SENDER = "+15550001"  # synthetic, as in the hand-made files
# Outbound classes: the input has direction OUT, as the payload sends it for an outbound message.
OUTBOUND = {"CL-MO-VISIBLE", "CL-MO-UNPERSISTED", "CL-MO-DATA", "CL-MO-RAW", "CL-MO-STK", "CL-MO-VVM"}


def class_mask(wire, old):
    if old and wire in NEW:
        return A
    return MATRIX[wire]


def snapshot(class_actions, rules):
    return {"classActions": class_actions, "guards": {"suplGraceMin": 5},
            "knownOriginators": {"exact": [], "prefix": []}, "preset": "CUSTOM", "revision": 0, "rules": rules,
            "schema": 1}


def inp(wire, originator, old):
    direction = "OUT" if wire in OUTBOUND and not old else "IN"
    return {"cbEmergency": False, "classId": wire, "contentType": None, "destPort": -1, "direction": direction,
            "originator": originator, "roaming": False, "slot": -1, "smsc": None, "text": None}


def expected(action, bit, mask, source, rule_id, clamp_reason="ACTION_NOT_ALLOWED"):
    ok = (mask & bit) != 0
    return {"action": action if ok else "ALLOW", "clampReason": None if ok else clamp_reason,
            "clampedFrom": None if ok else action, "hasRule": rule_id is not None, "ruleId": rule_id,
            "source": source}


def rule(rule_id, action, when):
    return {"action": action, "enabled": True, "id": rule_id, "name": "case", "origin": "USER", "priority": 0,
            "when": when}


def cases(wire, old):
    out = []
    mask = class_mask(wire, old)
    for action, bit in ACTIONS:
        out.append({"expected": expected(action, bit, mask, "CLASS_ACTION", None), "input": inp(wire, None, old),
                    "name": "class action " + action, "snapshot": snapshot({wire: action}, [])})
    if old:
        sender_mask = ALL if wire in OLD_SENDER else mask
    else:
        sender_mask = SENDER_RULE.get(wire, mask)
    for action, bit in ACTIONS:
        when = {"classes": [wire], "originator": {"op": "IS", "value": SENDER}}
        out.append({"expected": expected(action, bit, sender_mask, "RULE", 501), "input": inp(wire, SENDER, old),
                    "name": "rule with a sender condition -> " + action,
                    "snapshot": snapshot({}, [rule(501, action, when)])})
    if wire == "CL-NORMAL":
        # Without a sender/port/text condition CL-NORMAL rules allow A N; Q/B is BLOCKS_ALL_NORMAL.
        for action, bit in ACTIONS:
            e = expected(action, bit, AN, "RULE", 502, "BLOCKS_ALL_NORMAL")
            out.append({"expected": e, "input": inp(wire, None, old),
                        "name": "rule without a sender/port/text condition -> " + action,
                        "snapshot": snapshot({}, [rule(502, action, {"classes": [wire]})])})
    return out


def render(wire, old):
    return json.dumps({"cases": cases(wire, old), "class": wire}, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def main():
    d = sys.argv[1]
    old = "--old" in sys.argv
    check = "--check" in sys.argv
    diff = 0
    for wire in MATRIX:
        path = os.path.join(d, wire + ".json")
        text = render(wire, old)
        current = None
        if os.path.exists(path):
            with open(path, encoding="utf-8", newline="") as f:
                current = f.read()
        if current == text:
            continue
        diff += 1
        print(("DIFF " if check else "WRITE ") + wire)
        if not check:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)
    print("classes %d, changed %d" % (len(MATRIX), diff))
    if check and diff:
        sys.exit(1)


if __name__ == "__main__":
    main()
