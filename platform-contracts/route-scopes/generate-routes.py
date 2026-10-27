#!/usr/bin/env python3
"""route-scopes/generate-routes.py — INV-T-9042 (INV-Q-9038 A)

Generates `machine-routes.tsv` — one row per public machine API operation —
from the `x-principal` and `x-principal-also` markers of every
`*/openapi.yaml` of the tree (INV-REQ-584: each machine principal type has a
fixed permission set; INV-REQ-867; INV-AC-920, INV-AC-9149).

A row is an operation whose path starts with the exact prefix `/v1/` and whose
`x-principal` is not `ap-callback` (the AP notification route has no token at
the gateway). Header: method<TAB>path_template<TAB>scope<TAB>also_scope; method
is lower case, path_template as written in the contract, scope the
`x-principal` value, also_scope the `x-principal-also` value or `-`. Rows are
sorted by path_template, then method.

Fail closed (exit 1, naming the operation): an operation with a `/v1/` path
has no `x-principal`, a value outside the allowed set, a (method, path)
duplicate, an `x-principal-also` other than `certification`, or an
`x-principal-also` on an operation whose `x-principal` is not `ingest`.
`--check` exits 1 when the file differs from the derived rows in any byte.

Usage: generate-routes.py ROOT (--write FILE | --check FILE)
Standard library only (no YAML parser): the generator reads the regular layout
of `paths:` (path at indent 2, method at indent 4, `x-` keys at indent 6).
"""
import glob
import os
import re
import sys

HEADER = "method\tpath_template\tscope\talso_scope"
PREFIX = "/v1/"
SCOPES = ("ingest", "filing-workflow", "ap-callback", "reporting", "monitoring", "certification")
ALSO_SCOPE = "certification"
ALSO_ONLY_ON = "ingest"
METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
NONE = "-"

PATH_RE = re.compile(r"^  (\S.*?):\s*$")
METHOD_RE = re.compile(r"^    ([a-z]+):\s*$")
EXT_RE = re.compile(r"^      (x-principal|x-principal-also):[ \t]*(.*?)\s*$")


class RouteError(Exception):
    pass


def unquote(value):
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        return value[1:-1]
    return value


def operations(root):
    """Yield (file, method, path, {marker: value}) for every operation."""
    for f in sorted(glob.glob(os.path.join(root, "*", "openapi.yaml"))):
        rel = os.path.relpath(f, root)
        in_paths = False
        path = None
        op = None
        with open(f, encoding="utf-8") as fh:
            for line in fh:
                line = line.rstrip("\n")
                if re.match(r"^\S", line):
                    if op:
                        yield op
                        op = None
                    in_paths = line.startswith("paths:")
                    path = None
                    continue
                if not in_paths:
                    continue
                m = PATH_RE.match(line)
                if m:
                    if op:
                        yield op
                        op = None
                    path = unquote(m.group(1))
                    continue
                m = METHOD_RE.match(line)
                if m and path is not None and m.group(1) in METHODS:
                    if op:
                        yield op
                    op = (rel, m.group(1), path, {})
                    continue
                m = EXT_RE.match(line)
                if m and op:
                    op[3].setdefault(m.group(1), []).append(unquote(m.group(2)))
        if op:
            yield op


def derive(root):
    rows = []
    seen = {}
    for rel, method, path, marks in operations(root):
        if not path.startswith(PREFIX):
            continue
        name = "%s %s (%s)" % (method.upper(), path, rel)
        if (method, path) in seen:
            raise RouteError("%s duplicates %s" % (name, seen[(method, path)]))
        seen[(method, path)] = name
        principal = marks.get("x-principal", [])
        if len(principal) != 1 or principal[0] == "":
            raise RouteError("%s has no single x-principal" % name)
        scope = principal[0]
        if scope not in SCOPES:
            raise RouteError("%s has x-principal %r outside %s" % (name, scope, ", ".join(SCOPES)))
        also = marks.get("x-principal-also", [])
        if len(also) > 1:
            raise RouteError("%s repeats x-principal-also" % name)
        if also:
            if also[0] != ALSO_SCOPE:
                raise RouteError("%s has x-principal-also %r, only %r is allowed" % (name, also[0], ALSO_SCOPE))
            if scope != ALSO_ONLY_ON:
                raise RouteError("%s has x-principal-also on x-principal %r, only %r may carry it" % (name, scope, ALSO_ONLY_ON))
        if scope == "ap-callback":
            continue
        rows.append((path, method, scope, also[0] if also else NONE))
    rows.sort()
    return [HEADER] + ["\t".join((m, p, s, a)) for p, m, s, a in rows]


def render(lines):
    return "\n".join(lines) + "\n"


def main(argv):
    if len(argv) != 4 or argv[2] not in ("--write", "--check"):
        print("usage: generate-routes.py ROOT (--write FILE | --check FILE)", file=sys.stderr)
        return 2
    root, mode, target = argv[1], argv[2], argv[3]
    try:
        derived = derive(root)
    except RouteError as e:
        print("route-scopes: %s" % e, file=sys.stderr)
        return 1
    if mode == "--write":
        with open(target, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(render(derived))
        print("route-scopes: wrote %d rows to %s" % (len(derived) - 1, target))
        return 0
    try:
        with open(target, "rb") as fh:
            actual = fh.read()
    except OSError as e:
        print("route-scopes: cannot read %s: %s" % (target, e), file=sys.stderr)
        return 1
    expected = render(derived).encode("utf-8")
    if actual == expected:
        print("route-scopes: %s matches (%d rows)" % (target, len(derived) - 1))
        return 0
    have = actual.decode("utf-8", "replace").split("\n")
    want = derived + [""]
    for row in sorted(set(want) - set(have)):
        print("route-scopes: missing or different row: %r" % row, file=sys.stderr)
    for row in sorted(set(have) - set(want)):
        print("route-scopes: stale row in file: %r" % row, file=sys.stderr)
    print("route-scopes: %s differs from the derived rows; run --write" % target, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
