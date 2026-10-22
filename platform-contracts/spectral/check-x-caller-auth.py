#!/usr/bin/env python3
"""spectral/check-x-caller-auth.py — INV-T-8261 (INV-AC-8259; INV-REQ-1202; INV-Q-8201 A, INV-Q-8532).

Every operation of a listed document carries `x-caller-auth`, a string equal to exactly one of
end-user, signed-link, service-job. The listed documents are the lines of
<root>/spectral/x-caller-auth-documents.txt, one `<directory>/openapi.yaml` path per line. A blank
line, a comment line, a duplicate, whitespace or any other form fails. The operations whose value
is not end-user are the table NOT_END_USER below (operationId -> value); an operation of a listed
document must equal its table value, end-user when it is not in the table. Fails, naming file,
method and path, when:

  (a) an operation has no x-caller-auth, or the value is not a string equal to one of the three
      values (case, spaces, empty string, list and other look-alikes fail);
  (b) the value of an operation differs from the table (end-user when absent from it), or an
      operationId is missing or repeated in the document;
  (c) a listed path is missing or unreadable, or an unlisted document carries x-caller-auth
      (the list must be extended in the same change);
  (d) the list file has a malformed line or a duplicate.

Prints `caller-auth-check: N documents, M operations, K not end-user` and exits 0 only when
nothing fails. Usage: check-x-caller-auth.py <root>   (needs python3 and PyYAML)
"""
import re
import sys
from pathlib import Path

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
MARKER = "x-caller-auth"
VALUES = ("end-user", "signed-link", "service-job")
LIST_LINE = re.compile(r"[a-z][a-z0-9-]*/openapi\.yaml")
# Every operation whose value is not end-user (INV-AC-8259, INV-Q-8477): control-plane-svc jobs and the installer.
NOT_END_USER = {
    "openTenant": "service-job",  # control-plane-svc at go-live
    "closeTenant": "service-job",  # scheduled close job
    "recordInstallationAction": "service-job",  # installer, workload only
}


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
    paths = []
    lines = (root / "spectral" / "x-caller-auth-documents.txt").read_text().split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    for n, line in enumerate(lines, 1):
        if not LIST_LINE.fullmatch(line):
            errors.append(f"(d) x-caller-auth-documents.txt line {n}: {line!r} is not <directory>/openapi.yaml")
        elif line in paths:
            errors.append(f"(d) x-caller-auth-documents.txt line {n}: duplicate {line}")
        else:
            paths.append(line)
    return paths


def main(root):
    errors = []
    docs = ops = notend = 0
    try:
        listed = read_list(root, errors)
    except OSError as e:
        print(f"caller-auth-check: FAIL {e}")
        return 1
    for f in sorted(str(p.relative_to(root)) for p in root.glob("*/openapi.yaml")):
        if f in listed:
            continue
        try:
            doc = yaml.safe_load((root / f).read_text())
        except (OSError, yaml.YAMLError) as e:
            errors.append(f"(c) {f} unreadable: {e}")
            continue
        for method, path, op in operations(doc):
            if isinstance(op, dict) and MARKER in op:
                errors.append(f"(c) {f} {method} {path}: {MARKER} present but the document is not listed")
    for f in listed:
        try:
            doc = yaml.safe_load((root / f).read_text())
        except (OSError, yaml.YAMLError) as e:
            errors.append(f"(c) {f} missing or unreadable: {e}")
            continue
        docs += 1
        seen = set()
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
            expected = NOT_END_USER.get(oid, "end-user")
            if v != expected:
                errors.append(f"(b) {where}: {MARKER} is {v!r}, the table says {expected!r} for {oid}")
            if v != "end-user":
                notend += 1
    if errors:
        print("caller-auth-check: FAIL")
        print("\n".join(errors))
        return 1
    print(f"caller-auth-check: {docs} documents, {ops} operations, {notend} not end-user")
    return 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1])))
