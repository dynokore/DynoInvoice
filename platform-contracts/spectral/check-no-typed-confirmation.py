#!/usr/bin/env python3
"""spectral/check-no-typed-confirmation.py — INV-T-15826 (INV-AC-15821; INV-REQ-1013; INV-Q-15814).

A routine irreversible operation is confirmed by a distinct control, never by a typed phrase, so
its contract takes no input that could carry one. For every data row of
<root>/spectral/routine-irreversible-operations.tsv (directory, operationId) the file
<root>/<directory>/openapi.yaml must hold exactly one operation whose operationId equals the
row's by string equality (compared in its own directory only). It fails, naming the file and
operation, when:

  - the operation is missing, or is declared in two operations of that file;
  - its x-operation.reversibility is not exactly the string IRREVERSIBLE;
  - it has a requestBody key;
  - a parameter (a #/components/parameters/<name> $ref is resolved; an unresolvable one fails)
    has `in` other than `path`;
  - the YAML or the TSV is unreadable, the TSV header differs, a row has other than two
    fields, or a row is a duplicate.

Prints `no-typed-confirmation: N rows` and exits 0 only when nothing fails.

Usage: check-no-typed-confirmation.py <root>   (needs python3 and PyYAML)
"""
import sys
from pathlib import Path

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
HEADER = ["directory", "operationId"]
REF_PREFIX = "#/components/parameters/"


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


def components(doc) -> dict:
    comps = doc.get("components") if isinstance(doc, dict) else None
    params = comps.get("parameters") if isinstance(comps, dict) else None
    return params if isinstance(params, dict) else {}


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = Path(argv[1])
    errors = []
    rows = read_tsv(root / "spectral" / "routine-irreversible-operations.tsv", errors)
    docs = {}  # directory -> (operations, parameter components), or None when unreadable
    for d in sorted({r[0] for r in rows}):
        f = root / d / "openapi.yaml"
        try:
            doc = yaml.safe_load(f.read_text(encoding="utf-8"))
            docs[d] = (operations(doc), components(doc))
        except (yaml.YAMLError, OSError, UnicodeDecodeError) as ex:
            errors.append(f"{d}/openapi.yaml: unreadable YAML: {str(ex).splitlines()[0] if str(ex) else ex}")
            docs[d] = None
    for d, op_id in rows:
        if docs[d] is None:
            continue
        ops, comps = docs[d]
        where = f"{d}/openapi.yaml: operation {op_id}"
        matches = [o for o in ops if o.get("operationId") == op_id]
        if not matches:
            errors.append(f"{where}: missing from that file")
            continue
        if len(matches) > 1:
            errors.append(f"{where}: declared in {len(matches)} operations")
            continue
        op = matches[0]
        x_op = op.get("x-operation")
        declared = x_op.get("reversibility") if isinstance(x_op, dict) else None
        if declared != "IRREVERSIBLE" or not isinstance(declared, str):
            errors.append(f"{where}: x-operation.reversibility {declared!r} is not IRREVERSIBLE: the row names a routine irreversible operation")
        if "requestBody" in op:
            errors.append(f"{where}: has a requestBody, which could carry a typed confirmation phrase")
        params = op.get("parameters", [])
        if params is None:
            params = []
        if not isinstance(params, list):
            errors.append(f"{where}: parameters is not a list")
            params = []
        for i, p in enumerate(params):
            label = f"parameter {i}"
            if isinstance(p, dict) and "$ref" in p:
                ref = p["$ref"]
                name = ref[len(REF_PREFIX):] if isinstance(ref, str) and ref.startswith(REF_PREFIX) else None
                if name is None or name not in comps:
                    errors.append(f"{where}: parameter {i} $ref {ref!r} cannot be resolved")
                    continue
                label = f"parameter {name}"
                p = comps[name]
            loc = p.get("in") if isinstance(p, dict) else None
            if loc != "path":
                errors.append(f"{where}: {label} is in {loc!r}, only path parameters are allowed")
    if errors:
        print("no-typed-confirmation FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"no-typed-confirmation: {len(rows)} rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
