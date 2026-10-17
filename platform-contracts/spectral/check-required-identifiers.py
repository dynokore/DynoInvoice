#!/usr/bin/env python3
"""spectral/check-required-identifiers.py — INV-T-4909

Fails closed when a contract lacks an identifier its requirements name. A service directory
holds a table `<service>/required-identifiers.tsv`: one row per identifier written in backticks
in the published behavior or intent of a requirement that service implements. Columns, tab
separated: identifier, kind, file, requirement key. A first row whose first cell is `identifier`
is the header; blank lines and lines starting with `#` are ignored.

kind says where the identifier must be, found by equality as a whole token (never as a
substring, never in another case):
    property    a key of a `properties` mapping (an OpenAPI or JSON Schema property)
    operation   the value of an `operationId`
    enum_value  a member of an `enum` list
    query_parameter  the `name` of a parameter object whose `in` is `query` (INV-T-5959, INV-Q-6006 A)
    schema_property  `<Schema>.<property>` (split at the first dot): `components.schemas` has the key
                <Schema> and that schema's own `properties` has the key <property> (INV-T-6049, INV-Q-6105)
    schema_enum_value  `<Schema>.<property>.<MEMBER>` (split at the first two dots): that property's own
                inline schema has an `enum` list with a member equal to <MEMBER>. Neither scoped kind follows
                `allOf` or `$ref`, nested properties or inline schemas outside `components.schemas`
file is a YAML or JSON file under the row's own directory (a schema under <service>/schemas/ is
fine). The file is searched alone, so an identifier present only in another service's directory
does not count, and a file path that leaves the directory is a finding.

Exit 1 on: no table found at all; an empty table; a row with a missing column or an extra one;
an unknown kind; a row naming a file that does not exist, is not parseable or leaves the
directory; an identifier that is absent (also when it appears only as a substring or in another
case). Every finding names the table, the row, the identifier and the requirement key.

Usage: check-required-identifiers.py [<root>]      (default: the current directory)
Standard library plus PyYAML, as the other spectral checks.
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml

KINDS = ("property", "operation", "enum_value", "query_parameter", "schema_property", "schema_enum_value")
SCOPED = {"schema_property": 2, "schema_enum_value": 3}
SCOPED_FORM = {"schema_property": "<Schema>.<property>", "schema_enum_value": "<Schema>.<property>.<MEMBER>"}
HEADER = ("identifier", "kind", "file", "requirement")


def collect(doc) -> dict[str, set]:
    """All property names, operationIds and enum members of a parsed document."""
    found: dict[str, set] = {k: set() for k in KINDS}

    def walk(node) -> None:
        if isinstance(node, dict):
            if node.get("in") == "query" and isinstance(node.get("name"), str):
                found["query_parameter"].add(node["name"])
            for key, value in node.items():
                if key == "properties" and isinstance(value, dict):
                    found["property"].update(str(k) for k in value)
                elif key == "operationId" and isinstance(value, str):
                    found["operation"].add(value)
                elif key == "enum" and isinstance(value, list):
                    found["enum_value"].update(str(m) for m in value if isinstance(m, (str, int)))
                walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(doc)
    schemas = (doc.get("components") or {}).get("schemas") if isinstance(doc, dict) else None
    for sname, schema in (schemas.items() if isinstance(schemas, dict) else ()):
        props = schema.get("properties") if isinstance(schema, dict) else None
        for pname, prop in (props.items() if isinstance(props, dict) else ()):
            found["schema_property"].add((str(sname), str(pname)))
            members = prop.get("enum") if isinstance(prop, dict) else None
            for m in (members if isinstance(members, list) else ()):
                if isinstance(m, (str, int)):
                    found["schema_enum_value"].add((str(sname), str(pname), str(m)))
    return found


def check_table(table: Path, cache: dict[Path, dict[str, set[str]] | str]) -> list[str]:
    findings: list[str] = []
    rows = 0
    base = table.parent.resolve()
    for no, raw in enumerate(table.read_text().splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        cells = raw.split("\t")
        if cells[0].strip().lower() == HEADER[0] and rows == 0 and tuple(c.strip().lower() for c in cells[:3]) == HEADER[:3]:
            continue
        rows += 1
        where = f"{table}:{no}"
        if len(cells) != 4 or any(not c.strip() for c in cells):
            findings.append(f"{where}: row needs exactly four non-empty columns (identifier, kind, file, requirement key): {raw!r}")
            continue
        ident, kind, file, req = (c.strip() for c in cells)
        tag = f"{where}: {ident!r} ({kind}, {file}, {req})"
        if kind not in KINDS:
            findings.append(f"{tag}: unknown kind {kind!r}; expected one of {', '.join(KINDS)}")
            continue
        target = (base / file).resolve()
        if base not in target.parents:
            findings.append(f"{tag}: file leaves the directory {base}; only this service's files count")
            continue
        if target not in cache:
            if not target.is_file():
                cache[target] = f"file {file} does not exist"
            else:
                try:
                    cache[target] = collect(yaml.safe_load(target.read_text()))
                except yaml.YAMLError as e:
                    cache[target] = f"file {file} cannot be parsed: {str(e).splitlines()[0]}"
        loaded = cache[target]
        if isinstance(loaded, str):
            findings.append(f"{tag}: {loaded}")
        elif kind in SCOPED:
            parts = ident.split(".", SCOPED[kind] - 1)
            if len(parts) != SCOPED[kind] or any(not p for p in parts):
                findings.append(f"{tag}: the identifier cell of {kind} needs {SCOPED[kind]} non-empty dot-separated parts ({SCOPED_FORM[kind]})")
            elif tuple(parts) not in loaded[kind]:
                findings.append(f"{tag}: not found as a {kind} (whole-token, case-sensitive match) in {file}")
        elif ident not in loaded[kind]:
            findings.append(f"{tag}: not found as a {kind} (whole-token, case-sensitive match) in {file}")
    if rows == 0:
        findings.append(f"{table}: the table has no rows")
    return findings


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(".")
    tables = sorted(root.glob("*/required-identifiers.tsv"))
    if not tables:
        print(f"gst-ci: required-identifiers FAIL — no */required-identifiers.tsv found under {root}")
        return 1
    findings: list[str] = []
    cache: dict = {}
    for table in tables:
        findings += check_table(table, cache)
    if findings:
        print("gst-ci: required-identifiers FAIL")
        for f in findings:
            print(f"  - {f}")
        return 1
    print(f"gst-ci: required-identifiers OK — {len(tables)} table(s): {', '.join(str(t) for t in tables)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
