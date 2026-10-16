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

The three rules apply at every depth reached through properties and array
items; a local $ref ('#/...') is replaced by its target in its own document
(an unresolved one is a violation), a file $ref is compared as a string. Only
properties, required, type and items are read (allOf, oneOf, anyOf and nested
additionalProperties are not).

Usage: check-payload-compat.py <old.schema.json> <new.schema.json>
       check-payload-compat.py --new <new.schema.json>
Exits 0 if additive, 1 and names every violation otherwise. The --new form is
for a file with no base (new in the merge request): only the root
additionalProperties: false rule applies (API-41); the removed, retyped and
made-required rules need a base and are not applied.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from urllib.parse import unquote


def _resolve(doc: dict, ref: str):
    """Follow a local JSON Pointer ('#/...') inside doc; None if it does not resolve."""
    node = doc
    for part in ref[2:].split("/"):
        part = unquote(part).replace("~1", "/").replace("~0", "~")
        if isinstance(node, dict) and part in node:
            node = node[part]
        elif isinstance(node, list) and part.isdigit() and int(part) < len(node):
            node = node[int(part)]
        else:
            return None
    return node


def _follow(doc: dict, sub, path: str, violations: list[str]):
    """Replace a local-$ref subschema by its target. Returns (schema, ref_or_file_ref, followed)."""
    followed = False
    seen: set[str] = set()
    while isinstance(sub, dict) and isinstance(sub.get("$ref"), str):
        ref = sub["$ref"]
        if not ref.startswith("#/"):
            return sub, ref, followed
        target = _resolve(doc, ref) if ref not in seen else None
        seen.add(ref)
        if not isinstance(target, dict):
            violations.append(f"unresolved $ref '{ref}' at '{path}' (API-41)")
            return None, None, followed
        sub, followed = target, True
    return sub, None, followed


def _at(path: str) -> str:
    return f" at '{path}'" if path else ""


def _compare(old_doc: dict, new_doc: dict, old, new, path: str, done: set, violations: list[str]) -> None:
    old, old_file_ref, old_followed = _follow(old_doc, old, path, violations)
    new, new_file_ref, new_followed = _follow(new_doc, new, path, violations)
    if old is None or new is None:
        return
    if old_file_ref is not None or new_file_ref is not None:
        if old_file_ref != new_file_ref:
            violations.append(f"changed $ref at '{path}': {old_file_ref} -> {new_file_ref} (API-41)")
        return
    if not isinstance(old, dict) or not isinstance(new, dict):
        return
    if old_followed or new_followed:
        key = (id(old), id(new))
        if key in done:
            return
        done.add(key)

    old_props = old.get("properties", {})
    new_props = new.get("properties", {})
    old_required = set(old.get("required", []))
    new_required = set(new.get("required", []))

    for name, old_schema in old_props.items():
        if name not in new_props:
            violations.append(f"removed property '{name}'{_at(path)} (API-41)")
            continue
        child = f"{path}.{name}" if path else name
        o, o_file, _ = _follow(old_doc, old_schema, child, [])
        n, n_file, _ = _follow(new_doc, new_props[name], child, [])
        if o is not None and n is not None and o_file is None and n_file is None:
            old_type = o.get("type") if isinstance(o, dict) else None
            new_type = n.get("type") if isinstance(n, dict) else None
            if old_type is not None and old_type != new_type:
                violations.append(f"retyped property '{name}'{_at(path)}: {old_type} -> {new_type} (API-41)")
        _compare(old_doc, new_doc, old_schema, new_props[name], child, done, violations)

    newly_required = sorted(new_required - old_required)
    if newly_required:
        violations.append(f"made required (was optional or absent){_at(path)}: {newly_required} (API-41)")

    if isinstance(old.get("items"), dict) and isinstance(new.get("items"), dict):
        o, o_file, _ = _follow(old_doc, old["items"], f"{path}[]", [])
        n, n_file, _ = _follow(new_doc, new["items"], f"{path}[]", [])
        if isinstance(o, dict) and isinstance(n, dict) and o_file is None and n_file is None:
            if o.get("type") is not None and o.get("type") != n.get("type"):
                violations.append(f"retyped items at '{path}[]': {o.get('type')} -> {n.get('type')} (API-41)")
        _compare(old_doc, new_doc, old["items"], new["items"], f"{path}[]", done, violations)


def check(old: dict, new: dict) -> list[str]:
    violations: list[str] = []
    _compare(old, new, old, new, "", set(), violations)

    if new.get("additionalProperties") is False:
        violations.append("additionalProperties: false — a consumer must tolerate unknown fields (API-41, FR-K.4)")

    return violations


def main(argv: list[str]) -> int:
    if len(argv) == 3 and argv[1] == "--new":
        new = json.loads(Path(argv[2]).read_text())
        if new.get("additionalProperties") is False:
            print(f"gst-ci: new payload schema {argv[2]} sets additionalProperties: false"
                  " — a consumer must tolerate unknown fields (API-41, FR-K.4)")
            return 1
        return 0
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
