#!/usr/bin/env python3
"""spectral/check-caller-auth.py — INV-T-8261 (INV-AC-8259; INV-REQ-1202; INV-Q-8201 A).

Every operation of a listed document carries `x-caller-auth`, a string equal to exactly one of
end-user, signed-link, service-job. The listed documents are the data rows of
<root>/spectral/caller-auth-documents.tsv (directory, exceptions): `exceptions` is `-` or a
`;`-separated list of operationId=value pairs naming every operation whose value is not
end-user. Fails, naming file, method and path, when:

  (a) an operation of a listed document has no x-caller-auth, or the value is not a string equal
      to one of the three values (case, spaces, empty string, list and other look-alikes fail);
  (b) the operations whose value is not end-user differ, as a set of (operationId, value),
      from the exceptions of the row, or an operationId is missing or repeated in the document;
  (c) a listed document is missing or unreadable, or a document not listed carries x-caller-auth
      on an operation (the list must be extended in the same change);
  (d) the TSV header is not the expected one, a row has another number of fields, a directory
      repeats, or an exception entry is malformed.

Prints `caller-auth-check: N documents, M operations, K not end-user` and exits 0 only when
nothing fails. Usage: check-caller-auth.py <root>   (needs python3 and PyYAML)
"""
import sys
from pathlib import Path

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
MARKER = "x-caller-auth"
VALUES = ("end-user", "signed-link", "service-job")
HEADER = ["directory", "exceptions"]


def operations(doc):
    paths = doc.get("paths") if isinstance(doc, dict) else None
    if not isinstance(paths, dict):
        return
    for path, item in paths.items():
        if isinstance(item, dict):
            for method in METHODS:
                if method in item:
                    yield method, path, item[method]


def read_list(root, errors):
    rows = {}
    lines = (root / "spectral" / "caller-auth-documents.tsv").read_text().split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if not lines or lines[0].split("\t") != HEADER:
        errors.append("(d) caller-auth-documents.tsv header is not " + "\t".join(HEADER))
        return rows
    for n, line in enumerate(lines[1:], 2):
        f = line.split("\t")
        if len(f) != 2 or not f[0]:
            errors.append(f"(d) caller-auth-documents.tsv line {n}: expected 2 fields")
            continue
        if f[0] in rows:
            errors.append(f"(d) caller-auth-documents.tsv line {n}: duplicate directory {f[0]}")
            continue
        exc = {}
        if f[1] != "-":
            for entry in f[1].split(";"):
                op, eq, val = entry.partition("=")
                if not eq or not op or val not in VALUES or val == "end-user" or op in exc:
                    errors.append(f"(d) caller-auth-documents.tsv line {n}: malformed exception '{entry}'")
                else:
                    exc[op] = val
        rows[f[0]] = exc
    return rows


def main(root):
    errors = []
    docs = ops = notend = 0
    try:
        rows = read_list(root, errors)
    except OSError as e:
        print(f"caller-auth-check: FAIL {e}")
        return 1
    for d in sorted(p.parent.name for p in root.glob("*/openapi.yaml")):
        if d in rows:
            continue
        try:
            doc = yaml.safe_load((root / d / "openapi.yaml").read_text())
        except (OSError, yaml.YAMLError) as e:
            errors.append(f"(c) {d}/openapi.yaml unreadable: {e}")
            continue
        for method, path, op in operations(doc):
            if isinstance(op, dict) and MARKER in op:
                errors.append(f"(c) {d}/openapi.yaml {method} {path}: {MARKER} present but {d} is not listed")
    for d, exc in sorted(rows.items()):
        f = f"{d}/openapi.yaml"
        try:
            doc = yaml.safe_load((root / f).read_text())
        except (OSError, yaml.YAMLError) as e:
            errors.append(f"(c) {f} missing or unreadable: {e}")
            continue
        docs += 1
        seen, found = set(), {}
        for method, path, op in operations(doc):
            ops += 1
            where = f"{f} {method} {path}"
            if not isinstance(op, dict):
                errors.append(f"(a) {where}: operation is not a mapping")
                continue
            oid = op.get("operationId")
            if not isinstance(oid, str) or not oid or oid in seen:
                errors.append(f"(b) {where}: operationId missing or repeated")
            else:
                seen.add(oid)
            if MARKER not in op:
                errors.append(f"(a) {where}: {MARKER} is missing")
                continue
            v = op[MARKER]
            if not isinstance(v, str) or v not in VALUES:
                errors.append(f"(a) {where}: {MARKER} value {v!r} is not one of {', '.join(VALUES)}")
                continue
            if v != "end-user":
                notend += 1
                found[oid] = v
        if found != exc:
            errors.append(f"(b) {f}: operations not end-user {sorted(found.items())} differ from the list {sorted(exc.items())}")
    if errors:
        print("caller-auth-check: FAIL")
        print("\n".join(errors))
        return 1
    print(f"caller-auth-check: {docs} documents, {ops} operations, {notend} not end-user")
    return 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1])))
