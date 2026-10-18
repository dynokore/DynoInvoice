#!/usr/bin/env python3
"""spectral/check-error-code-catalogue.py — INV-T-6947 (INV-Q-7062 A)

Fails closed when an `x-error-codes` member of any contract is not a code of the error catalogue.
Reads `<root>/catalogue/errors/error-codes.yaml` (a mapping whose key `error_codes` is a non-empty
list of mappings, each with a non-empty string `code`) and every `<root>/*/openapi.yaml` (one
directory level, sorted). Every mapping of every contract that has the key `x-error-codes` is
checked: the value must be a list and each member a string equal, case-sensitive and untrimmed,
to a `code` of the catalogue. A code that appears only in a `condition` text or in an operation
description never counts. An empty list passes (the presence rule is spectral's).

Exit 1, with a finding naming the contract, the `operationId` of the mapping (or `(none)`) and the
member, on: a member that is not a catalogue code; a member that is not a string; a value that is
not a list; a catalogue that is missing, unparseable, not of that shape or with an empty list or an
entry without a string `code`; no `*/openapi.yaml` found; a contract that cannot be parsed.
Exit 0 prints `gst-ci: error-code-catalogue OK` and the number of members and contracts.

Usage: check-error-code-catalogue.py [<root>]      (default root: the current directory)
Standard library plus PyYAML, as the other spectral checks.
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml


def load_catalogue(root: Path, findings: list[str]) -> set[str] | None:
    path = root / "catalogue" / "errors" / "error-codes.yaml"
    rel = "catalogue/errors/error-codes.yaml"
    if not path.is_file():
        findings.append(f"{rel}: the catalogue does not exist")
        return None
    try:
        doc = yaml.safe_load(path.read_text())
    except (yaml.YAMLError, OSError) as e:
        findings.append(f"{rel}: the catalogue cannot be parsed: {str(e).splitlines()[0]}")
        return None
    entries = doc.get("error_codes") if isinstance(doc, dict) else None
    if not isinstance(entries, list) or not entries:
        findings.append(f"{rel}: the key error_codes must be a non-empty list")
        return None
    codes: set[str] = set()
    for i, entry in enumerate(entries, 1):
        code = entry.get("code") if isinstance(entry, dict) else None
        if not isinstance(code, str) or not code:
            findings.append(f"{rel}: entry {i} has no non-empty string code")
            continue
        codes.add(code)
    return codes


def walk(node, contract: str, codes: set[str], findings: list[str], count: list[int]) -> None:
    if isinstance(node, dict):
        if "x-error-codes" in node:
            op = node.get("operationId")
            op = op if isinstance(op, str) and op else "(none)"
            value = node["x-error-codes"]
            if not isinstance(value, list):
                findings.append(f"{contract}: operation {op}: x-error-codes is not a list")
            else:
                for member in value:
                    count[0] += 1
                    if not isinstance(member, str):
                        findings.append(f"{contract}: operation {op}: x-error-codes member {member!r} is not a string")
                    elif member not in codes:
                        findings.append(f"{contract}: operation {op}: x-error-codes member {member!r} is not a code of the catalogue")
        for v in node.values():
            walk(v, contract, codes, findings, count)
    elif isinstance(node, list):
        for v in node:
            walk(v, contract, codes, findings, count)


def main(argv: list[str]) -> int:
    root = Path(argv[1] if len(argv) > 1 else ".")
    findings: list[str] = []
    codes = load_catalogue(root, findings)
    contracts = sorted(root.glob("*/openapi.yaml"))
    if not contracts:
        findings.append("no */openapi.yaml found")
    count = [0]
    for c in contracts:
        name = c.relative_to(root).as_posix()
        try:
            doc = yaml.safe_load(c.read_text())
        except (yaml.YAMLError, OSError) as e:
            findings.append(f"{name}: the contract cannot be parsed: {str(e).splitlines()[0]}")
            continue
        if codes is not None:
            walk(doc, name, codes, findings, count)
    if findings:
        for f in findings:
            print(f"gst-ci: error-code-catalogue FAIL — {f}")
        return 1
    print(f"gst-ci: error-code-catalogue OK — {count[0]} member(s) in {len(contracts)} contract(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
