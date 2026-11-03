#!/usr/bin/env python3
"""spectral/check-compensation-cost.py — INV-T-15754 (INV-AC-15750; INV-REQ-1014; INV-Q-15744).

Every operation whose x-operation.reversibility is the string COMPENSABLE (case sensitive, no
trimming) in <root>/*/openapi.yaml must carry x-operation.compensationCost, a string with at least
one non-space character, unless the pair (directory, operationId) is a row of
<root>/spectral/compensation-cost-staged.tsv (a ratchet baseline: later slices remove rows, none adds).
It fails, printing `<directory>/openapi.yaml: operation <operationId>: <reason>`, when:

  - a COMPENSABLE operation has no statement and no staged row (a text of only spaces is missing);
  - a staged row names a pair that is not a COMPENSABLE operation of that directory (stale), or one
    that already carries a statement (the row must be removed);
  - a row is a duplicate or has another field count, or the header differs from
    directory<TAB>operationId;
  - the TSV or a YAML file is unreadable, or an operationId is declared twice in one file.

Prints `compensation-cost-check: <N> compensable operations, <S> staged` and exits 0 only when
nothing fails.

Usage: check-compensation-cost.py <root>   (needs python3 and PyYAML)
"""
import sys
from pathlib import Path

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
HEADER = ["directory", "operationId"]


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


def operations(doc) -> list:
    """Every operation object of the document's paths."""
    found = []
    paths = doc.get("paths") if isinstance(doc, dict) else None
    if isinstance(paths, dict):
        for item in paths.values():
            if isinstance(item, dict):
                found.extend(item[m] for m in METHODS if isinstance(item.get(m), dict))
    return found


def has_statement(op: dict) -> bool:
    x_op = op.get("x-operation")
    cost = x_op.get("compensationCost") if isinstance(x_op, dict) else None
    return isinstance(cost, str) and cost.strip(" ") != ""


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = Path(argv[1])
    errors = []
    rows = read_tsv(root / "spectral" / "compensation-cost-staged.tsv", errors)
    staged = set(rows)
    compensable = {}  # (directory, operationId) -> has statement
    for f in sorted(root.glob("*/openapi.yaml")):
        d = f.parent.name
        try:
            ops = operations(yaml.safe_load(f.read_text(encoding="utf-8")))
        except (yaml.YAMLError, OSError, UnicodeDecodeError) as ex:
            errors.append(f"{d}/openapi.yaml: unreadable YAML: {str(ex).splitlines()[0] if str(ex) else ex}")
            continue
        ids = {}
        for o in ops:
            ids[o.get("operationId")] = ids.get(o.get("operationId"), 0) + 1
        for op_id, count in ids.items():
            if count > 1 and op_id is not None:
                errors.append(f"{d}/openapi.yaml: operation {op_id}: declared in {count} operations")
        for o in ops:
            x_op = o.get("x-operation")
            if isinstance(x_op, dict) and x_op.get("reversibility") == "COMPENSABLE":
                op_id = o.get("operationId")
                has = has_statement(o)
                compensable[(d, op_id)] = compensable.get((d, op_id), False) or has
                if not has and (d, op_id) not in staged:
                    errors.append(f"{d}/openapi.yaml: operation {op_id}: COMPENSABLE without a "
                                  f"x-operation.compensationCost statement and not staged")
    for d, op_id in rows:
        where = f"{d}/openapi.yaml: operation {op_id}"
        if (d, op_id) not in compensable:
            errors.append(f"{where}: stale staged row, not a COMPENSABLE operation of that directory")
        elif compensable[(d, op_id)]:
            errors.append(f"{where}: already carries a compensationCost statement, remove the staged row")
    if errors:
        print("compensation-cost-check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"compensation-cost-check: {len(compensable)} compensable operations, {len(rows)} staged")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
