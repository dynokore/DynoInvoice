#!/usr/bin/env python3
"""spectral/check-principal-also.py — INV-T-9152 (INV-AC-9149; AUTHZ-01; INV-Q-9032 A).

An ingest operation that `certification` may also call (in a SANDBOX workspace only, NFR-S.4) carries the
marker `x-principal-also: certification`; the gateway and the services read it. This script proves the marker
is well formed and complete. For every <root>/<dir>/openapi.yaml it fails, naming the file, method and path, when:

  (a) the value is not the string `certification` (exact; `Certification`, `certification ` with a trailing
      space, the boolean true and a sequence all fail);
  (b) the path does not start with the exact prefix /v1/, or the operation's x-principal is not `ingest`;
  (c) the marked operations differ from the data rows of <root>/spectral/principal-also-operations.tsv
      (directory, method, path).

An unreadable YAML or TSV, a TSV header other than the expected one, a row with another number of fields, or a
duplicate row also fails. Prints `principal-also-check: N marked` and exits 0 only when nothing fails.

Usage: check-principal-also.py <root>   (needs python3 and PyYAML)
"""
import sys
from pathlib import Path

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
MARKER = "x-principal-also"
VALUE = "certification"
PATH_PREFIX = "/v1/"
PRIMARY = "ingest"
HEADER = ["directory", "method", "path"]


def read_tsv(path: Path, errors: list) -> list:
    """The data rows of a TSV as tuples; problems go to errors."""
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
    rows = read_tsv(root / "spectral" / "principal-also-operations.tsv", errors)
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
                if not isinstance(value, str) or value != VALUE:
                    errors.append(f"{where}: (a) {MARKER} value {value!r} is not the string {VALUE!r}")
                if not (isinstance(path, str) and path.startswith(PATH_PREFIX)):
                    errors.append(f"{where}: (b) path does not start with {PATH_PREFIX!r}")
                if op.get("x-principal") != PRIMARY:
                    errors.append(f"{where}: (b) x-principal is {op.get('x-principal')!r}, not {PRIMARY!r}")
    expected = set(rows)
    for key in sorted(expected - marked):
        errors.append(f"{key[0]}: {key[1]} {key[2]}: (c) row of principal-also-operations.tsv has no marked operation")
    for key in sorted(marked - expected):
        errors.append(f"{key[0]}: {key[1]} {key[2]}: (c) marked operation has no row in principal-also-operations.tsv")
    if errors:
        print("principal-also-check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"principal-also-check: {len(marked)} marked")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
