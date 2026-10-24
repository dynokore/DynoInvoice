#!/usr/bin/env python3
"""spectral/check-entity-ref.py — INV-T-8516, INV-T-9630 (INV-AC-8515, INV-AC-9629; API-24, API-25; INV-Q-8513 A, INV-Q-9617 A).

An operation whose request body names the entity it acts on carries the marker
`x-entity-ref: <property>` or `x-entity-ref: [<property>, ...]`; service-runtime refuses with
403 SRT-004 a request whose property (every element of an array property) lies outside the
end-user principal's entity set. This script proves the
marker is well formed and complete. For every <root>/<dir>/openapi.yaml it fails,
naming the file, method and path, when:

  (a) an operation has x-entity-ref and the value is not one string or a non-empty
      sequence of distinct strings, each matching ^[A-Za-z][A-Za-z0-9]{0,39}$ as a
      whole, or the operation's JSON request-body schema ($ref into the same file
      and allOf followed) has, for a name, no top-level property of exactly that name
      with type: string or type: array whose items are type: string;
  (b) an operation has x-entity-ref and its x-sync-edge is absent, a sequence, or
      not a string of the form console-bff-><callee> (a system-flow callee is
      never marked, API-25); the callee * is accepted in service-runtime only;
  (c) the marked operations differ from the data rows of
      <root>/spectral/entity-ref-operations.tsv (directory, method, path,
      property), the property column being the names joined by a comma in the order
      written;
  (d) an operation whose x-sync-edge is a string starting with console-bff-> and
      whose request body, resolved fully, has the top-level property entityRef or
      entityRefs (a candidate) carries no marker, or a marker that does not name every
      one of those candidate properties the body holds, and its (directory, operationId)
      is neither in
      <root>/spectral/entity-ref-staged.tsv nor in SERVLET_CHECKED;
  (e) a staged pair names an operation that does not exist, is not a candidate of
      (d), or is marked with a marker that already names every candidate property it
      holds;
  (f) an operation whose x-sync-edge is a string starting with console-bff-> (or
      that is marked) has a request body the script cannot fully resolve: a $ref
      that does not start with #/, whose target does not exist or that forms a
      cycle (in the body schema or an allOf/oneOf/anyOf branch, transitively), or
      a content type other than application/json with a schema. Such an
      operation is never skipped.

An unreadable YAML, a TSV header other than the expected one, a row with another
number of fields, or a duplicate row also fails. Prints
`entity-ref-check: N marked, M staged` and exits 0 only when nothing fails.

Usage: check-entity-ref.py <root>   (needs python3 and PyYAML)
"""
import re
import sys
from pathlib import Path
from urllib.parse import unquote

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
MARKER = "x-entity-ref"
VALUE = re.compile(r"[A-Za-z][A-Za-z0-9]{0,39}")
EDGE = re.compile(r"console-bff->([a-z][a-z0-9-]*-svc|\*)")
BFF_PREFIX = "console-bff->"
CANDIDATE_PROPERTIES = ("entityRef", "entityRefs")
SHARED_RUNTIME = "service-runtime"
# The servlets of these operations already call EndUserPrincipal.covers (platform-libs JobCommandServlet,
# PolicyCommandServlet), so they are neither marked nor staged. Compared by equality.
SERVLET_CHECKED = frozenset(
    (SHARED_RUNTIME, op) for op in ("runJobNow", "pauseJob", "resumeJob", "setPolicy", "acknowledgeControlRelaxation")
)
OPERATIONS_HEADER = ["directory", "method", "path", "property"]
STAGED_HEADER = ["directory", "operationId"]


def read_tsv(path: Path, header: list, errors: list) -> list:
    """The data rows of a TSV as tuples; problems go to errors."""
    try:
        lines = path.read_text(encoding="utf-8").split("\n")
    except OSError as ex:
        errors.append(f"{path.name}: unreadable: {ex}")
        return []
    if lines and lines[-1] == "":
        lines.pop()
    if not lines or lines[0].split("\t") != header:
        errors.append(f"{path.name}: the header line must be {chr(9).join(header)}")
        return []
    rows, seen = [], set()
    for n, line in enumerate(lines[1:], 2):
        fields = tuple(line.split("\t"))
        if len(fields) != len(header):
            errors.append(f"{path.name}: line {n} has {len(fields)} fields, expected {len(header)}")
        elif fields in seen:
            errors.append(f"{path.name}: line {n} is a duplicate row")
        else:
            seen.add(fields)
            rows.append(fields)
    return rows


def pointer(doc, ref: str):
    """Target of a same-file '#/...' reference, or KeyError."""
    node = doc
    for part in ref[2:].split("/"):
        part = unquote(part).replace("~1", "/").replace("~0", "~")
        if isinstance(node, dict) and part in node:
            node = node[part]
        elif isinstance(node, list) and part.isdigit() and int(part) < len(node):
            node = node[int(part)]
        else:
            raise KeyError(ref)
    return node


def follow(doc, node, stack: tuple, problems: list):
    """Dereference a node that is a $ref; returns (node, stack) or (None, stack) after recording a problem."""
    while isinstance(node, dict) and "$ref" in node:
        ref = node["$ref"]
        if not isinstance(ref, str) or not ref.startswith("#/"):
            problems.append(f"$ref {ref!r} does not start with #/")
            return None, stack
        if ref in stack:
            problems.append(f"$ref {ref!r} forms a cycle")
            return None, stack
        try:
            target = pointer(doc, ref)
        except KeyError:
            problems.append(f"$ref {ref!r} has no target")
            return None, stack
        stack += (ref,)
        node = target
    return node, stack


def walk_schema(doc, node, stack: tuple, props: dict, problems: list, collect: bool) -> None:
    """Resolve a schema fully: record every $ref problem; collect top-level properties through $ref and allOf."""
    if isinstance(node, dict) and "$ref" in node:
        siblings = {k: v for k, v in node.items() if k != "$ref"}
        target, stack2 = follow(doc, {"$ref": node["$ref"]}, stack, problems)
        if target is None:
            return
        walk_schema(doc, target, stack2, props, problems, collect)
        node = siblings
    if not isinstance(node, dict):
        return
    if collect and isinstance(node.get("properties"), dict):
        for name, sch in node["properties"].items():
            props.setdefault(name, (doc, sch, stack))
    for key in ("allOf", "oneOf", "anyOf"):
        branches = node.get(key)
        if isinstance(branches, list):
            for b in branches:
                walk_schema(doc, b, stack, props, problems, collect and key == "allOf")


def property_type(doc, entry):
    """'string' for type: string, 'string[]' for type: array with items of type: string, else None."""
    _, sch, stack = entry
    problems = []
    sch, stack = follow(doc, sch, stack, problems)
    if not isinstance(sch, dict) or problems:
        return None
    if sch.get("type") == "string":
        return "string"
    if sch.get("type") == "array" and isinstance(sch.get("items"), dict):
        items, _ = follow(doc, sch["items"], stack, problems)
        if isinstance(items, dict) and not problems and items.get("type") == "string":
            return "string[]"
    return None


def marker_names(value):
    """The names of a well-formed marker value as a tuple in the order written, else None."""
    names = (value,) if isinstance(value, str) else tuple(value) if isinstance(value, list) and value else None
    if names is None or not all(isinstance(n, str) and VALUE.fullmatch(n) for n in names) or len(set(names)) != len(names):
        return None
    return names


def resolve_body(doc, op: dict):
    """(top-level properties, problems) of the operation's JSON request body."""
    props, problems = {}, []
    rb = op.get("requestBody")
    if rb is None:
        return props, problems
    rb, _ = follow(doc, rb, (), problems)
    content = rb.get("content") if isinstance(rb, dict) else None
    if not isinstance(content, dict):
        return props, problems
    for ctype, media in content.items():
        if ctype != "application/json":
            if isinstance(media, dict) and media.get("schema") is not None:
                problems.append(f"request body content type {ctype!r} is not application/json")
        elif isinstance(media, dict) and media.get("schema") is not None:
            walk_schema(doc, media["schema"], (), props, problems, True)
    return props, problems


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = Path(argv[1])
    errors = []
    op_rows = read_tsv(root / "spectral" / "entity-ref-operations.tsv", OPERATIONS_HEADER, errors)
    staged_rows = read_tsv(root / "spectral" / "entity-ref-staged.tsv", STAGED_HEADER, errors)
    marked = {}  # (dir, METHOD, path) -> (operationId, value)
    ops_by_id = {}  # (dir, operationId) -> candidate?
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
                if not isinstance(op, dict):
                    continue
                where = f"{d}: {m.upper()} {path}"
                edge = op.get("x-sync-edge")
                is_bff = isinstance(edge, str) and edge.startswith(BFF_PREFIX)
                is_marked = MARKER in op
                props, problems = resolve_body(doc, op) if (is_bff or is_marked) else ({}, [])
                if is_bff or is_marked:
                    for p in problems:
                        errors.append(f"{where}: (f) request body cannot be fully resolved: {p}")
                held = tuple(n for n in CANDIDATE_PROPERTIES if n in props) if is_bff else ()
                names = marker_names(op[MARKER]) if is_marked else None
                complete = names is not None and all(n in names for n in held)
                if isinstance(op.get("operationId"), str):
                    ops_by_id[(d, op["operationId"])] = (bool(held), complete, is_marked)
                if is_marked:
                    value = op[MARKER]
                    marked[(d, m.upper(), path)] = ",".join(names) if names is not None else value
                    if names is None:
                        errors.append(f"{where}: (a) x-entity-ref value {value!r} does not match ^[A-Za-z][A-Za-z0-9]{{0,39}}$ as one name "
                                      f"or a non-empty sequence of distinct names")
                    else:
                        for n in names:
                            if n not in props or property_type(doc, props[n]) is None:
                                errors.append(f"{where}: (a) request body has no top-level property {n!r} with type string "
                                              f"or array of string")
                    if not (isinstance(edge, str) and EDGE.fullmatch(edge) and (edge != "console-bff->*" or d == SHARED_RUNTIME)):
                        errors.append(f"{where}: (b) x-entity-ref needs x-sync-edge console-bff-><callee>, found {edge!r}")
                if held and not complete:
                    pair = (d, op.get("operationId"))
                    if pair not in SERVLET_CHECKED and pair not in set(staged_rows):
                        what = "no x-entity-ref" if not is_marked else "an x-entity-ref that does not name every property"
                        errors.append(f"{where}: (d) console-bff operation with body {','.join(held)} has {what} and is not staged")
    expected = {}
    for dd, mm, pp, prop in op_rows:
        expected[(dd, mm, pp)] = prop
    for key, prop in expected.items():
        if key not in marked:
            errors.append(f"{key[0]}: {key[1]} {key[2]}: (c) row of entity-ref-operations.tsv has no marked operation")
        elif marked[key] != prop:
            errors.append(f"{key[0]}: {key[1]} {key[2]}: (c) marker value {marked[key]!r} differs from the row property {prop!r}")
    for key in marked:
        if key not in expected:
            errors.append(f"{key[0]}: {key[1]} {key[2]}: (c) marked operation has no row in entity-ref-operations.tsv")
    for pair in staged_rows:
        if pair not in ops_by_id:
            errors.append(f"{pair[0]}: {pair[1]}: (e) staged operation does not exist")
        else:
            candidate, complete, _ = ops_by_id[pair]
            if not candidate:
                errors.append(f"{pair[0]}: {pair[1]}: (e) staged operation is not a candidate")
            elif complete:
                errors.append(f"{pair[0]}: {pair[1]}: (e) staged operation is already marked")
    if errors:
        print("entity-ref-check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"entity-ref-check: {len(marked)} marked, {len(staged_rows)} staged")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
