#!/usr/bin/env python3
"""spectral/check-degraded-allow.py — INV-T-9077 (INV-AC-9075; INV-REQ-1201; INV-Q-9065 A).

In degraded mode (the authority replica has no local or fallback answer) a machine action is
allowed only on an operation the contract marks `x-degraded-allow: true`. This script proves
the marker is well formed and that the marked set is the reviewed one. For every
<root>/<dir>/openapi.yaml it fails, naming the file, method and path, when:

  (a) an operation has x-degraded-allow and the value is not the boolean true (false, the
      string "true", 1 and a sequence all fail);
  (b) a marked operation's path does not start with /v1/, or its x-principal is not one of
      ingest, filing-workflow, reporting, ap-callback, certification;
  (c) the marked operations differ from the data rows of
      <root>/spectral/degraded-allow-operations.tsv (directory, method, path).

An unreadable YAML or TSV, a TSV header other than the expected one, a row with another
number of fields, or a duplicate row also fails. Prints `degraded-allow-check: N marked` and
exits 0 only when nothing fails.

Usage: check-degraded-allow.py <root>   (needs python3 and PyYAML)
"""
import sys
from pathlib import Path

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
MARKER = "x-degraded-allow"
PATH_PREFIX = "/v1/"
PRINCIPALS = frozenset(("ingest", "filing-workflow", "reporting", "ap-callback", "certification"))
HEADER = ["directory", "method", "path"]


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
        elif fields in seen:
            errors.append(f"{path.name}: line {n} is a duplicate row")
        else:
            seen.add(fields)
            rows.append(fields)
    return rows


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = Path(argv[1])
    errors = []
    rows = read_tsv(root / "spectral" / "degraded-allow-operations.tsv", errors)
    marked = set()  # (dir, METHOD, path)
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
                marked.add((d, m.upper(), path))
                value = op[MARKER]
                if value is not True:
                    errors.append(f"{where}: (a) x-degraded-allow value {value!r} is not the boolean true")
                if not (isinstance(path, str) and path.startswith(PATH_PREFIX)):
                    errors.append(f"{where}: (b) a marked operation's path must start with {PATH_PREFIX}")
                principal = op.get("x-principal")
                if not (isinstance(principal, str) and principal in PRINCIPALS):
                    errors.append(f"{where}: (b) x-principal {principal!r} is not one of {', '.join(sorted(PRINCIPALS))}")
    expected = set(rows)
    for key in sorted(expected - marked):
        errors.append(f"{key[0]}: {key[1]} {key[2]}: (c) row of degraded-allow-operations.tsv has no marked operation")
    for key in sorted(marked - expected):
        errors.append(f"{key[0]}: {key[1]} {key[2]}: (c) marked operation has no row in degraded-allow-operations.tsv")
    if errors:
        print("degraded-allow-check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"degraded-allow-check: {len(marked)} marked")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
