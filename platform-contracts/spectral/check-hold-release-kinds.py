#!/usr/bin/env python3
"""spectral/check-hold-release-kinds.py — INV-T-15822 (INV-AC-15818; INV-REQ-1016; INV-Q-1953, INV-Q-15802).

Every HOLD code of <root>/catalogue/errors/error-codes.yaml whose disposition starts with
AUTO_RELEASE must have exactly one row in <root>/catalogue/hold-release-kinds.tsv
(header `code<TAB>kind`), and the kind must be exactly DATA or ELECTION (case sensitive, no
trimming). The table must name no other code (compared by string equality), and the five codes
of INV-Q-1953 carry the ruled kind: MDM-001 and SUB-011 DATA; PER-011, PER-012 and PAY-016
ELECTION.

Prints `hold-release-kinds: N rows` and exits 0 only when nothing fails; otherwise prints
`hold-release-kinds FAILED:` and one line per finding, exit 1.

Usage: check-hold-release-kinds.py <root>   (needs python3 and PyYAML)
"""
import sys
from pathlib import Path

import yaml

KINDS = ("DATA", "ELECTION")
RULED = {"MDM-001": "DATA", "SUB-011": "DATA", "PER-011": "ELECTION", "PER-012": "ELECTION", "PAY-016": "ELECTION"}
TABLE = "catalogue/hold-release-kinds.tsv"
CATALOGUE = "catalogue/errors/error-codes.yaml"


def auto_releasing(root: Path, errors: list) -> set:
    """The codes of HOLD rows whose disposition starts with AUTO_RELEASE."""
    try:
        doc = yaml.safe_load((root / CATALOGUE).read_text(encoding="utf-8"))
        rows = doc["error_codes"]
        return {r["code"] for r in rows
                if r.get("severity") == "HOLD" and str(r.get("disposition", "")).startswith("AUTO_RELEASE")}
    except Exception as ex:  # unreadable, unparsable or not the expected shape
        errors.append(f"{CATALOGUE}: cannot be read: {type(ex).__name__}: {ex}")
        return set()


def table_rows(root: Path, errors: list) -> list:
    """(line number, code, kind) of each well-formed data row; faults go to errors."""
    try:
        lines = (root / TABLE).read_bytes().decode("utf-8").split("\n")
    except (OSError, UnicodeDecodeError) as ex:
        errors.append(f"{TABLE}: cannot be read: {type(ex).__name__}: {ex}")
        return []
    if lines and lines[-1] == "":
        lines.pop()
    if not lines or lines[0] != "code\tkind":
        errors.append(f"{TABLE}: the header line must be exactly code<TAB>kind")
        return []
    rows, seen = [], set()
    for n, line in enumerate(lines[1:], 2):
        fields = line.split("\t")
        if len(fields) != 2:
            errors.append(f"{TABLE}: line {n} has {len(fields)} fields, not two")
            continue
        code, kind = fields
        if code in seen:
            errors.append(f"{TABLE}: line {n} repeats the code {code!r}")
            continue
        seen.add(code)
        if kind not in KINDS:
            errors.append(f"{TABLE}: line {n}: kind {kind!r} of {code!r} is not exactly DATA or ELECTION")
            continue
        rows.append((n, code, kind))
    return rows


def main(argv: list) -> int:
    if len(argv) != 2:
        print("usage: check-hold-release-kinds.py <root>", file=sys.stderr)
        return 2
    root = Path(argv[1])
    errors: list = []
    expected = auto_releasing(root, errors)
    unreadable = bool(errors)
    rows = table_rows(root, errors)
    unreadable = unreadable or any(e.startswith(f"{TABLE}: cannot") or "header line" in e for e in errors)
    have = {code: kind for _, code, kind in rows}
    if not unreadable:
        for code in sorted(expected - set(have)):
            errors.append(f"{TABLE}: {code} is an auto-releasing HOLD code without a kind")
    for n, code, _ in rows:
        if code not in expected:
            errors.append(f"{TABLE}: line {n}: {code!r} is not a HOLD code with an AUTO_RELEASE disposition (stale)")
    for code, kind in RULED.items():
        if code in have and have[code] != kind:
            errors.append(f"{TABLE}: {code} is {have[code]}, the owner ruled {kind} (INV-Q-1953)")
    if errors:
        print("hold-release-kinds FAILED:")
        for e in errors:
            print(e)
        return 1
    print(f"hold-release-kinds: {len(rows)} rows")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
