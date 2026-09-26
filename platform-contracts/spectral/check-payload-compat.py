#!/usr/bin/env python3
"""spectral/check-payload-compat.py — INV-T-2849 (T5)

API-41 / FR-K.4's "JSON Schema compatibility check of each payload against
the previous train's" — the companion `asyncapi diff` needs (STK-45's own
tool does not deeply diff payload schemas; STK-45's own text lists both
mechanisms for exactly that reason).

Additive-only: a payload schema may add an optional property, but never
remove one, retype one, or make one required that was not required before
(API-41: "Nothing is removed, renamed, retyped or made required"), and never
sets additionalProperties: false (a consumer must tolerate unknown fields,
API-41/CS's tolerant-reader rule).

Usage: check-payload-compat.py <old.schema.json> <new.schema.json>
Exits 0 if additive, 1 and names every violation otherwise.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path


def check(old: dict, new: dict) -> list[str]:
    violations: list[str] = []
    old_props: dict = old.get("properties", {})
    new_props: dict = new.get("properties", {})
    old_required = set(old.get("required", []))
    new_required = set(new.get("required", []))

    for name, old_schema in old_props.items():
        if name not in new_props:
            violations.append(f"removed property '{name}' (API-41)")
            continue
        old_type = old_schema.get("type")
        new_type = new_props[name].get("type")
        if old_type is not None and old_type != new_type:
            violations.append(f"retyped property '{name}': {old_type} -> {new_type} (API-41)")

    newly_required = sorted(new_required - old_required)
    if newly_required:
        violations.append(f"made required (was optional or absent): {newly_required} (API-41)")

    if new.get("additionalProperties") is False:
        violations.append("additionalProperties: false — a consumer must tolerate unknown fields (API-41, FR-K.4)")

    return violations


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    old = json.loads(Path(argv[1]).read_text())
    new = json.loads(Path(argv[2]).read_text())
    violations = check(old, new)
    if violations:
        print(f"gst-ci: payload schema is not additive-only ({argv[1]} -> {argv[2]}):")
        for v in violations:
            print(f"  - {v}")
        return 1
    print(f"gst-ci: payload schema is additive-only ({argv[1]} -> {argv[2]}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
