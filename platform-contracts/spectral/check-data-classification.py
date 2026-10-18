#!/usr/bin/env python3
"""spectral/check-data-classification.py — INV-T-6443 (INV-Q-6441 option A; INV-REQ-883, INV-AC-1915)

Fails when a contract field has no personal-data class or a wrong one. Each service directory holds
`data-classification.tsv` (header `file` TAB `unit` TAB `class`; blank lines and lines starting with `#`
ignored) with one row per unit of each file the required table names for that directory.
The required table (default: data-classification-required.tsv next to this script; header `directory`
TAB `file`) is compared with the last path component of each argument, by equality.

Units:
  - openapi.yaml: `<Schema>.<property>` for each key of `properties` of each components.schemas entry
    that is a mapping with a `properties` mapping.
  - *.schema.json: the keys of the root `properties`; a root property that is an object whose non-empty
    `properties` members are all classification wrappers (properties keys exactly `value` and
    `classification`) is replaced by one unit `<root property>.<member>` per member.
Floor (openapi only): the class of `S.p` must be at or above the highest class of every unit reachable
from its schema node through `$ref` (`#/components/schemas/<N>` only), `items`, `additionalProperties`,
`properties`, `allOf`, `oneOf`, `anyOf`; the exact error-envelope reference adds nothing; any other
`$ref` is a finding. In a *.schema.json file a `$ref` inside a unit is a finding.
Wrappers: `classification` must be `{type: string, enum: [<row class>]}`; a file with a wrapper must
carry `$defs.DataClass` = string enum NONE, IDENTIFYING, SENSITIVE in that order.

Exit 1 on any finding; every finding is one line `<directory>: <file>: <unit>: <reason>`.
Usage: check-data-classification.py [--required <tsv>] <service-dir> [<service-dir> ...]
Standard library plus PyYAML.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml

CLASSES = ("NONE", "IDENTIFYING", "SENSITIVE")
ENVELOPE_REF = "../service-runtime/schemas/error-envelope.schema.json"
LOCAL_REF = re.compile(r"^#/components/schemas/([^/]+)$")
CHILD_KEYS = ("items", "additionalProperties")
LIST_KEYS = ("allOf", "oneOf", "anyOf")

findings: list[str] = []


def find(directory: str, file: str, unit: str, reason: str) -> None:
    findings.append(f"{directory}: {file}: {unit}: {reason}")


def read_table(path: Path, header: list[str]):
    """Rows (list of cells) after the header, or (None, reason)."""
    try:
        text = path.read_text()
    except OSError as e:
        return None, f"cannot read ({e.__class__.__name__})"
    lines = [ln for ln in text.split("\n") if ln.strip() != "" and not ln.startswith("#")]
    if not lines:
        return None, "the table is empty"
    if lines[0].split("\t") != header:
        return None, "the header is not exactly " + " TAB ".join(header)
    if len(lines) == 1:
        return None, "the table has no rows"
    return [ln.split("\t") for ln in lines[1:]], None


def is_wrapper(node) -> bool:
    props = node.get("properties") if isinstance(node, dict) else None
    return isinstance(props, dict) and set(props) == {"value", "classification"}


def expanded_root(node) -> bool:
    props = node.get("properties") if isinstance(node, dict) else None
    return isinstance(props, dict) and len(props) > 0 and all(is_wrapper(v) for v in props.values())


def contains_ref(node, in_properties: bool = False) -> bool:
    if isinstance(node, dict):
        if not in_properties and "$ref" in node:
            return True
        return any(contains_ref(v, k == "properties" and not in_properties) for k, v in node.items())
    if isinstance(node, list):
        return any(contains_ref(v) for v in node)
    return False


def schema_units(name: str, schema) -> list[str]:
    props = schema.get("properties") if isinstance(schema, dict) else None
    return [f"{name}.{p}" for p in props] if isinstance(props, dict) else []


def walk(node, schemas, reach: set[str], seen: set[str], bad: list[str]) -> None:
    if not isinstance(node, dict):
        return
    if "$ref" in node:
        ref = node["$ref"]
        m = LOCAL_REF.match(ref) if isinstance(ref, str) else None
        if m and m.group(1) in schemas:
            n = m.group(1)
            if n not in seen:
                seen.add(n)
                reach.update(schema_units(n, schemas[n]))
                walk(schemas[n], schemas, reach, seen, bad)
        elif ref != ENVELOPE_REF:
            bad.append(str(ref))
    for k in CHILD_KEYS:
        walk(node.get(k), schemas, reach, seen, bad)
    props = node.get("properties")
    if isinstance(props, dict):
        for v in props.values():
            walk(v, schemas, reach, seen, bad)
    for k in LIST_KEYS:
        if isinstance(node.get(k), list):
            for v in node[k]:
                walk(v, schemas, reach, seen, bad)


def load_units(directory: Path, name: str, file: str):
    """(units: dict unit -> node, wrapper members: set, doc, error)"""
    path = directory / file
    try:
        text = path.read_text()
    except OSError as e:
        return None, None, None, f"required file unreadable ({e.__class__.__name__})"
    try:
        if file == "openapi.yaml":
            doc = yaml.safe_load(text)
        elif file.endswith(".schema.json"):
            doc = json.loads(text)
        else:
            return None, None, None, "required file kind is not openapi.yaml or *.schema.json"
    except (yaml.YAMLError, ValueError):
        return None, None, None, "required file unparseable"
    if not isinstance(doc, dict):
        return None, None, None, "required file unparseable (not a mapping)"
    units: dict[str, object] = {}
    wrappers: set[str] = set()
    if file == "openapi.yaml":
        comps = doc.get("components")
        schemas = comps.get("schemas") if isinstance(comps, dict) else None
        if isinstance(schemas, dict):
            for s, sv in schemas.items():
                if isinstance(sv, dict) and isinstance(sv.get("properties"), dict):
                    for p, pv in sv["properties"].items():
                        units[f"{s}.{p}"] = pv
    else:
        props = doc.get("properties")
        if isinstance(props, dict):
            for p, pv in props.items():
                if expanded_root(pv):
                    for m, mv in pv["properties"].items():
                        units[f"{p}.{m}"] = mv
                        wrappers.add(f"{p}.{m}")
                else:
                    units[str(p)] = pv
    return units, wrappers, doc, None


def check_dir(directory: Path, name: str, files: list[str]) -> None:
    rows, err = read_table(directory / "data-classification.tsv", ["file", "unit", "class"])
    if err:
        find(name, "data-classification.tsv", "-", err)
        return
    registry: dict[tuple[str, str], str] = {}
    for cells in rows:
        if len(cells) != 3:
            find(name, "data-classification.tsv", "-", f"row with {len(cells)} columns: {cells[0]!r}")
            continue
        f, u, c = cells
        if c not in CLASSES:
            find(name, f, u, f"class {c!r} is not NONE, IDENTIFYING or SENSITIVE")
            continue
        if (f, u) in registry:
            find(name, f, u, "duplicate row")
            continue
        registry[(f, u)] = c
        if f not in files:
            find(name, f, u, "row file is not a required file of the directory")
    for file in files:
        units, wrappers, doc, err = load_units(directory, name, file)
        if err:
            find(name, file, "-", err)
            continue
        if not units:
            find(name, file, "-", "the file has no unit")
        for u in units:
            if (file, u) not in registry:
                find(name, file, u, "unit without a row")
        for (f, u) in registry:
            if f == file and u not in units:
                find(name, file, u, "row names a unit that does not exist in the file")
        cls = {u: registry[(file, u)] for u in units if (file, u) in registry}
        if file == "openapi.yaml":
            check_floor(name, file, doc, units, cls)
        else:
            for u, node in units.items():
                if contains_ref(node):
                    find(name, file, u, "$ref inside a payload unit")
        for u in sorted(wrappers):
            if u not in cls:
                continue
            check_wrapper(name, file, u, units[u], cls[u])
        if wrappers:
            check_dataclass(name, file, doc)


def check_floor(name, file, doc, units, cls) -> None:
    schemas = doc["components"]["schemas"]
    for u, node in units.items():
        if u not in cls:
            continue
        reach: set[str] = set()
        bad: list[str] = []
        walk(node, schemas, reach, set(), bad)
        for b in bad:
            find(name, file, u, f"$ref {b!r} is not a local schema reference or the error envelope")
        worst = None
        for r in sorted(reach):
            if r in cls and CLASSES.index(cls[r]) > CLASSES.index(cls[u]) and (
                    worst is None or CLASSES.index(cls[r]) > CLASSES.index(cls[worst])):
                worst = r
        if worst:
            find(name, file, u, f"class {cls[u]} is below the reachable unit {worst} ({cls[worst]})")


def check_wrapper(name, file, u, node, klass) -> None:
    c = node["properties"]["classification"] if is_wrapper(node) else None
    enum = c.get("enum") if isinstance(c, dict) else None
    if not (isinstance(c, dict) and c.get("type") == "string" and isinstance(enum, list)
            and len(enum) == 1 and enum[0] == klass):
        find(name, file, u, f"wrapper classification is not a string enum of exactly [{klass}]")


def check_dataclass(name, file, doc) -> None:
    defs = doc.get("$defs")
    dc = defs.get("DataClass") if isinstance(defs, dict) else None
    if not (isinstance(dc, dict) and dc.get("type") == "string" and dc.get("enum") == list(CLASSES)):
        find(name, file, "$defs.DataClass", "missing or not a string enum NONE, IDENTIFYING, SENSITIVE in that order")


def main(argv: list[str]) -> int:
    required_path = Path(__file__).resolve().parent / "data-classification-required.tsv"
    args = argv[:]
    if args[:1] == ["--required"]:
        if len(args) < 2:
            print("usage: check-data-classification.py [--required <tsv>] <service-dir> ...")
            return 2
        required_path, args = Path(args[1]), args[2:]
    if not args:
        print("usage: check-data-classification.py [--required <tsv>] <service-dir> ...")
        return 2
    req: dict[str, list[str]] = {}
    rows, err = read_table(required_path, ["directory", "file"])
    if err:
        find("-", str(required_path), "-", "required table: " + err)
        rows = []
    seen_rows = set()
    for cells in rows:
        if len(cells) != 2:
            find("-", str(required_path), "-", f"required row with {len(cells)} columns: {cells[0]!r}")
        elif tuple(cells) in seen_rows:
            find(cells[0], cells[1], "-", "duplicate required row")
        else:
            seen_rows.add(tuple(cells))
            req.setdefault(cells[0], []).append(cells[1])
    given: dict[str, Path] = {}
    for a in args:
        n = Path(a).name
        if n in given:
            find(n, "-", "-", "directory passed twice")
        given[n] = Path(a)
    for n, p in given.items():
        if n not in req:
            find(n, "-", "-", "directory is not in the required table")
        elif not err:
            check_dir(p, n, req[n])
    for n in req:
        if n not in given:
            find(n, "-", "-", "required directory was not passed")
    for line in findings:
        print(line)
    if findings:
        print(f"check-data-classification: {len(findings)} finding(s).")
        return 1
    print("check-data-classification: OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
