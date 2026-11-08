#!/usr/bin/env python3
"""spectral/check-operation-permissions.py — INV-T-18670 (INV-AC-18673; OWASP ASVS V4, NIST SP 800-53 AC-3).

Ties the permission of an operation to the role-permissions catalogue, fail closed. The table
<root>/spectral/operation-permissions.tsv (header: directory, operationId, permission) is the one reviewed
mapping. It fails, naming the directory and operationId, when:

  (a) a row names a (directory, operationId) pair that is not exactly one operation of <directory>/openapi.yaml;
  (b) that operation's x-operation has a kind other than PERMISSION, a permission not equal to the row cell, or a
      role property;
  (c) a row's permission is not a `key` of `permissions` in catalogue/role-permissions/role-permissions.yaml;
  (d) in a directory that has a row, an operation whose x-operation has kind PERMISSION or a permission property
      has no row;
  (e) the header differs, a row has another number of fields, a (directory, operationId) pair occurs twice, or the
      table has no data row.

All comparisons are by equality: no trimming, case folding, prefix or glob.
Prints `operation-permissions-check: N rows` and exits 0 only when nothing fails.

Usage: check-operation-permissions.py <root>   (needs python3 and PyYAML)
"""
import sys
from pathlib import Path

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
HEADER = ["directory", "operationId", "permission"]
TABLE = Path("spectral") / "operation-permissions.tsv"
CATALOGUE = Path("catalogue") / "role-permissions" / "role-permissions.yaml"


def read_tsv(path: Path, errors: list) -> list:
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
        elif fields[:2] in seen:
            errors.append(f"{path.name}: line {n} is a duplicate row for {fields[0]} {fields[1]}")
        else:
            seen.add(fields[:2])
            rows.append(fields)
    if not rows and not errors:
        errors.append(f"{path.name}: the table has no data row")
    return rows


def operations(root: Path, directory: str, errors: list) -> list:
    """All operation objects of <directory>/openapi.yaml."""
    f = root / directory / "openapi.yaml"
    try:
        doc = yaml.safe_load(f.read_text(encoding="utf-8"))
    except (yaml.YAMLError, OSError, UnicodeDecodeError) as ex:
        errors.append(f"{directory}: openapi.yaml unreadable: {str(ex).splitlines()[0] if str(ex) else ex}")
        return []
    paths = doc.get("paths") if isinstance(doc, dict) else None
    ops = []
    for item in (paths.values() if isinstance(paths, dict) else ()):
        for m in METHODS:
            if isinstance(item, dict) and isinstance(item.get(m), dict):
                ops.append(item[m])
    return ops


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = Path(argv[1])
    errors: list = []
    rows = read_tsv(root / TABLE, errors)
    try:
        cat = yaml.safe_load((root / CATALOGUE).read_text(encoding="utf-8"))
        keys = {p.get("key") for p in cat["permissions"] if isinstance(p, dict)}
    except (yaml.YAMLError, OSError, UnicodeDecodeError, KeyError, TypeError) as ex:
        errors.append(f"{CATALOGUE}: unreadable or without permissions: {ex}")
        keys = set()
    cache: dict = {}
    for d, op_id, perm in rows:
        who = f"{d} {op_id}"
        if perm not in keys:
            errors.append(f"{who}: (c) permission {perm!r} is not a key of the role-permissions catalogue")
        if d not in cache:
            cache[d] = operations(root, d, errors)
        found = [o for o in cache[d] if o.get("operationId") == op_id]
        if len(found) != 1:
            errors.append(f"{who}: (a) {len(found)} operations with this operationId in {d}/openapi.yaml, expected exactly 1")
            continue
        xo = found[0].get("x-operation")
        xo = xo if isinstance(xo, dict) else {}
        if xo.get("kind") != "PERMISSION":
            errors.append(f"{who}: (b) x-operation kind is {xo.get('kind')!r}, not 'PERMISSION'")
        if "permission" not in xo or xo["permission"] != perm:
            errors.append(f"{who}: (b) x-operation permission is {xo.get('permission')!r}, the row says {perm!r}")
        if "role" in xo:
            errors.append(f"{who}: (b) x-operation has a role property")
    listed = {(r[0], r[1]) for r in rows}
    for d in sorted({r[0] for r in rows}):
        for o in cache.get(d, ()):
            xo = o.get("x-operation")
            if isinstance(xo, dict) and (xo.get("kind") == "PERMISSION" or "permission" in xo) \
                    and (d, o.get("operationId")) not in listed:
                errors.append(f"{d} {o.get('operationId')}: (d) x-operation carries a permission and the operation has no row")
    if errors:
        print("operation-permissions-check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"operation-permissions-check: {len(rows)} rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
