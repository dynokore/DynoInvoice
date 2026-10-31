#!/usr/bin/env python3
"""spectral/check-reversibility-floor.py — INV-T-13354 (INV-AC-13350, INV-AC-13351; INV-REQ-964, INV-REQ-1001; INV-Q-13344).

The class an operation declares in its contract may be stricter than the published class, never
weaker. The scale is UNDOABLE < COMPENSABLE < IRREVERSIBLE. For every data row of
<root>/spectral/reversibility-floor.tsv (directory, operationId, floor) the file
<root>/<directory>/openapi.yaml must hold exactly one operation whose operationId equals the
row's by string equality, and its x-operation.reversibility must be a string in the enum (case
sensitive, no trimming) not lower than the floor. It fails, naming the file and operation, when:

  - the operation is missing from that directory, or is declared in two operations of it;
  - reversibility is absent, not a string, or outside the enum;
  - the declared class is lower than the floor;
  - the YAML or the TSV is unreadable, the TSV header differs, a row has another number of
    fields, a floor is not in the enum, or a row is a duplicate.

Prints `reversibility-floor-check: N rows` and exits 0 only when nothing fails.

Usage: check-reversibility-floor.py <root>   (needs python3 and PyYAML)
"""
import sys
from pathlib import Path

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
SCALE = ("UNDOABLE", "COMPENSABLE", "IRREVERSIBLE")
HEADER = ["directory", "operationId", "floor"]


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
        elif fields[2] not in SCALE:
            errors.append(f"{path.name}: line {n} floor {fields[2]!r} is not one of {', '.join(SCALE)}")
        else:
            seen.add(fields)
            rows.append(fields)
    return rows


def operations(doc) -> list:
    """Every operation object of the document's paths."""
    found = []
    paths = doc.get("paths") if isinstance(doc, dict) else None
    if isinstance(paths, dict):
        for item in paths.values():
            if isinstance(item, dict):
                found.extend(item[m] for m in METHODS if isinstance(item.get(m), dict))
    return found


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = Path(argv[1])
    errors = []
    rows = read_tsv(root / "spectral" / "reversibility-floor.tsv", errors)
    docs = {}  # directory -> operations list, or None when unreadable
    for d in sorted({r[0] for r in rows}):
        f = root / d / "openapi.yaml"
        try:
            docs[d] = operations(yaml.safe_load(f.read_text(encoding="utf-8")))
        except (yaml.YAMLError, OSError, UnicodeDecodeError) as ex:
            errors.append(f"{d}/openapi.yaml: unreadable YAML: {str(ex).splitlines()[0] if str(ex) else ex}")
            docs[d] = None
    for d, op_id, floor in rows:
        ops = docs[d]
        if ops is None:
            continue
        where = f"{d}/openapi.yaml: operation {op_id}"
        matches = [o for o in ops if o.get("operationId") == op_id]
        if not matches:
            errors.append(f"{where}: missing from that directory")
            continue
        if len(matches) > 1:
            errors.append(f"{where}: declared in {len(matches)} operations")
            continue
        x_op = matches[0].get("x-operation")
        declared = x_op.get("reversibility") if isinstance(x_op, dict) else None
        if not isinstance(declared, str):
            errors.append(f"{where}: x-operation.reversibility {declared!r} is absent or not a string")
        elif declared not in SCALE:
            errors.append(f"{where}: x-operation.reversibility {declared!r} is not one of {', '.join(SCALE)}")
        elif SCALE.index(declared) < SCALE.index(floor):
            errors.append(f"{where}: declared {declared} is lower than the floor {floor}")
    if errors:
        print("reversibility-floor-check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"reversibility-floor-check: {len(rows)} rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
