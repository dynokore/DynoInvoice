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

    operation_error_code  `<operationId>.<CODE>` (split at the first dot): an operation whose own
                `operationId` equals <operationId> has an `x-error-codes` list with a member equal to <CODE>
                (case-sensitive; a description, a summary or another operation never counts; INV-T-6937,
                INV-Q-7029 A)

--base <sha> (INV-T-6937, INV-Q-7027 A) adds a ratchet: every `<dir>/required-identifiers.tsv` that exists at
that commit must still exist, and every data row of it, keyed by the trimmed (identifier, kind, file) triple, must
still be a data row of the same table at the head. A removed row is a finding naming the table, the identifier,
the kind and the file. Comment lines, blank lines and the header are not rows; added rows and a changed
requirement cell are not findings. A table missing at the head, an empty sha, and an sha that is not a commit of
the repository at the root each exit 1. There is no exemption for a retired row: removing one is an owner matter.
Without --base the behaviour is the plain check above.

Usage: check-required-identifiers.py [--base <sha>] [<root>]      (default root: the current directory)
Standard library plus PyYAML, as the other spectral checks.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

import yaml

KINDS = ("property", "operation", "enum_value", "query_parameter", "schema_property", "schema_enum_value", "operation_error_code")
SCOPED = {"schema_property": 2, "schema_enum_value": 3, "operation_error_code": 2}
SCOPED_FORM = {"schema_property": "<Schema>.<property>", "schema_enum_value": "<Schema>.<property>.<MEMBER>", "operation_error_code": "<operationId>.<CODE>"}
HEADER = ("identifier", "kind", "file", "requirement")


def collect(doc) -> dict[str, set]:
    """All property names, operationIds and enum members of a parsed document."""
    found: dict[str, set] = {k: set() for k in KINDS}

    def walk(node) -> None:
        if isinstance(node, dict):
            if node.get("in") == "query" and isinstance(node.get("name"), str):
                found["query_parameter"].add(node["name"])
            codes = node.get("x-error-codes")
            if isinstance(node.get("operationId"), str) and isinstance(codes, list):
                found["operation_error_code"].update((node["operationId"], c) for c in codes if isinstance(c, str))
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


def row_keys(text: str) -> set[tuple[str, str, str]]:
    """The (identifier, kind, file) key of every data row of a table; comments, blanks and the header are not rows."""
    keys: set[tuple[str, str, str]] = set()
    first = True
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        cells = raw.split("\t")
        if first and tuple(c.strip().lower() for c in cells[:3]) == HEADER[:3]:
            first = False
            continue
        first = False
        if len(cells) == 4 and all(c.strip() for c in cells):
            keys.add(tuple(c.strip() for c in cells[:3]))
    return keys


def check_base(root: Path, sha: str) -> list[str]:
    """INV-Q-7027 A: a row of a table at the base commit must still be a row of the same table at the head."""
    if not sha.strip() or sha.startswith("-"):
        return [f"--base needs a commit sha, got {sha!r}"]

    def git(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)

    if git("cat-file", "-e", f"{sha}^{{commit}}").returncode != 0:
        return [f"--base {sha!r} is not a commit of the repository at {root}"]
    listing = git("ls-tree", "-r", "--name-only", sha)
    if listing.returncode != 0:
        return [f"--base {sha!r}: git ls-tree failed: {listing.stderr.strip()}"]
    findings: list[str] = []
    for name in sorted(n for n in listing.stdout.splitlines() if re.fullmatch(r"[^/]+/required-identifiers\.tsv", n)):
        head = root / name
        if not head.is_file():
            findings.append(f"{name}: exists at {sha} and is missing at the head")
            continue
        before = git("show", f"{sha}:{name}")
        if before.returncode != 0:
            findings.append(f"{name}: cannot read it at {sha}: {before.stderr.strip()}")
            continue
        now = row_keys(head.read_text())
        for ident, kind, file in sorted(row_keys(before.stdout) - now):
            findings.append(f"{name}: row removed since {sha}: {ident!r} ({kind}, {file})")
    return findings


def main(argv: list[str]) -> int:
    args = argv[1:]
    base = None
    if args[:1] == ["--base"]:
        if len(args) < 2:
            print("gst-ci: required-identifiers FAIL — --base needs a commit sha")
            return 1
        base, args = args[1], args[2:]
    root = Path(args[0]) if args else Path(".")
    tables = sorted(root.glob("*/required-identifiers.tsv"))
    if not tables:
        print(f"gst-ci: required-identifiers FAIL — no */required-identifiers.tsv found under {root}")
        return 1
    findings: list[str] = []
    cache: dict = {}
    for table in tables:
        findings += check_table(table, cache)
    if base is not None:
        findings += check_base(root, base)
    if findings:
        print("gst-ci: required-identifiers FAIL")
        for f in findings:
            print(f"  - {f}")
        return 1
    print(f"gst-ci: required-identifiers OK — {len(tables)} table(s): {', '.join(str(t) for t in tables)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
