#!/usr/bin/env python3
"""spectral/check-break-glass-actions.py — INV-T-9308 (INV-AC-9304; INV-REQ-896; INV-Q-9119 A, INV-Q-9285 A).

While a break-glass session is open, an approver may call only the operations the contract marks
`x-break-glass-action: <ACTION>`. This script proves the marker is well formed and that the marked
set is the reviewed one. For every <root>/<dir>/openapi.yaml it fails, naming the file, method and
path, when:

  (a) an operation has x-break-glass-action and the value is not exactly one of the strings VIEW,
      READINESS, DRAIN, REPLAY (lower case, a sequence, a boolean and a number all fail);
  (b) a marked operation is not an end-user operation: its x-caller-auth is present and not
      end-user, or its x-principal is workload, or its path starts with neither /v1/ nor
      /internal/v1/;
  (c) the marked operations differ from the data rows of
      <root>/spectral/break-glass-actions-operations.tsv (directory, method, path, action); the
      action of a row must equal the operation's marker.

An unreadable YAML or TSV, a TSV header other than the expected one, a row with another number of
fields, or a duplicate row also fails. Prints `break-glass-actions-check: N marked` and exits 0
only when nothing fails.

Usage: check-break-glass-actions.py <root>   (needs python3 and PyYAML)
"""
import sys
from pathlib import Path

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
MARKER = "x-break-glass-action"
ACTIONS = ("VIEW", "READINESS", "DRAIN", "REPLAY")
PATH_PREFIXES = ("/v1/", "/internal/v1/")
HEADER = ["directory", "method", "path", "action"]


def read_tsv(path: Path, errors: list) -> list:
    """The data rows of the TSV as tuples; problems go to errors."""
    try:
        lines = path.read_text(encoding="utf-8").split("\n")
    except (OSError, UnicodeDecodeError) as ex:
        errors.append(f"{path.name}: unreadable: {ex}")
        return []
    if lines and lines[-1] == "":
        lines.pop()
    if not lines or lines[0].split("\t") != HEADER:
        errors.append(f"{path.name}: the header line must be {chr(9).join(HEADER)}")
        return []
    rows, seen = [], set()
    for n, line in enumerate(lines[1:], 2):
        fields = tuple(line.split("\t"))
        if len(fields) != len(HEADER):
            errors.append(f"{path.name}: line {n} has {len(fields)} fields, expected {len(HEADER)}")
        elif fields[:3] in seen:
            errors.append(f"{path.name}: line {n} is a duplicate row")
        else:
            seen.add(fields[:3])
            rows.append(fields)
    return rows


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = Path(argv[1])
    errors = []
    rows = read_tsv(root / "spectral" / "break-glass-actions-operations.tsv", errors)
    marked = {}  # (dir, METHOD, path) -> marker value
    for f in sorted(root.glob("*/openapi.yaml")):
        d = f.parent.name
        try:
            doc = yaml.safe_load(f.read_text(encoding="utf-8"))
        except (yaml.YAMLError, OSError, UnicodeDecodeError) as ex:
            errors.append(f"{d}/openapi.yaml: unreadable YAML: {str(ex).splitlines()[0] if str(ex) else ex}")
            continue
        paths = doc.get("paths") if isinstance(doc, dict) else None
        if not isinstance(paths, dict):
            continue
        for path, item in paths.items():
            if not isinstance(item, dict):
                continue
            for m in METHODS:
                op = item.get(m)
                if not isinstance(op, dict) or MARKER not in op:
                    continue
                where = f"{d}: {m.upper()} {path}"
                value = op[MARKER]
                marked[(d, m.upper(), path)] = value
                if not (isinstance(value, str) and value in ACTIONS):
                    errors.append(f"{where}: (a) x-break-glass-action value {value!r} is not one of {', '.join(ACTIONS)}")
                if not (isinstance(path, str) and path.startswith(PATH_PREFIXES)):
                    errors.append(f"{where}: (b) a marked operation's path must start with {' or '.join(PATH_PREFIXES)}")
                if "x-caller-auth" in op and op["x-caller-auth"] != "end-user":
                    errors.append(f"{where}: (b) x-caller-auth {op['x-caller-auth']!r} is not end-user")
                if op.get("x-principal") == "workload":
                    errors.append(f"{where}: (b) x-principal workload is not an end-user operation")
    for f in sorted(root.glob("*/required-identifiers.tsv")):
        try:
            text = f.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as ex:
            errors.append(f"{f.parent.name}/{f.name}: unreadable: {ex}")
            continue
        if any(MARKER in line.split("\t") for line in text.split("\n") if not line.startswith("#")):
            errors.append(f"{f.parent.name}/{f.name}: {MARKER} is an extension key and must be no required-identifiers row")
    expected = {r[:3]: r[3] for r in rows}
    for key in sorted(expected.keys() - marked.keys()):
        errors.append(f"{key[0]}: {key[1]} {key[2]}: (c) row of break-glass-actions-operations.tsv has no marked operation")
    for key in sorted(marked.keys() - expected.keys()):
        errors.append(f"{key[0]}: {key[1]} {key[2]}: (c) marked operation has no row in break-glass-actions-operations.tsv")
    for key in sorted(marked.keys() & expected.keys()):
        if marked[key] != expected[key]:
            errors.append(f"{key[0]}: {key[1]} {key[2]}: (c) row action {expected[key]!r} differs from the marker {marked[key]!r}")
    if errors:
        print("break-glass-actions-check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"break-glass-actions-check: {len(marked)} marked")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
