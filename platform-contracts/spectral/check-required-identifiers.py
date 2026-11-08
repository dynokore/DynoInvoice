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
    root_property  a key of the document's own top-level `properties` mapping; it does not follow `allOf`, `$ref`,
                `$defs`, `definitions`, nested properties or a `properties` key of any other mapping (INV-T-13720, INV-Q-13717 A)
    query_parameter  the `name` of a parameter object whose `in` is `query` (INV-T-5959, INV-Q-6006 A)
    schema_property  `<Schema>.<property>` (split at the first dot): `components.schemas` has the key
                <Schema> and that schema's own `properties` has the key <property> (INV-T-6049, INV-Q-6105)
    schema_enum_value  `<Schema>.<property>.<MEMBER>` (split at the first two dots): that property's own
                inline schema has an `enum` list with a member equal to <MEMBER>. Neither scoped kind follows
                `allOf` or `$ref`, nested properties or inline schemas outside `components.schemas`
    asyncapi_message  a key of `components.messages` of the row's file (an AsyncAPI message name; a `channels` key, a message
                `name` or `title` value and a description never count; INV-T-18374)
    asyncapi_operation  a key of the top-level `operations` mapping of the row's file (an AsyncAPI operation key; it is not an
                `operationId` value, and the kind `operation` is unchanged and does not read it; INV-T-18374)
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
the kind and the file. Comment lines, blank lines and the header are not rows; added rows are not findings (a changed
requirement cell is one, see the owner rule below). A table missing at the head, an empty sha, and an sha that is not a commit of
the repository at the root each exit 1. A removed row is exempt only through a line of the rename map
`spectral/required-identifiers-renames.tsv` read from the base commit (never the working tree; INV-T-13510, INV-Q-11109 A): the
map has exactly one line for that table and old (identifier, kind, file), and the table at the head holds a data row with the
new triple of that line and the requirement cell of the removed row; every comparison is by equality of the trimmed cell. Every
data line of the base map is checked and a malformed, unruled, duplicated or self-mapping line is a finding naming its line
number. The map exempts rows, never a table.
A retirement line (INV-T-17435, INV-Q-17427 A) has the token RETIRED in each of new_identifier, new_kind and new_file and an
`INV-Q-<number>` key in ruling. It exempts a removed row only when its table and old triple equal the row's, the table at the head
holds no row with that triple, and the element is absent at the head (kind operation: no operationId equal to old_identifier in
<dir>/old_file; other kinds: the lookup of the plain check finds nothing). A line with RETIRED in only some of the three cells is
a finding naming its line number.
Without --base the behaviour is the plain check above, except that the two ratchet rules below do not run (they need a baseline).

Completeness ratchet (INV-T-18955): M(head) and M(base) are the sets of (directory, operationId, code) of the `x-error-codes` lists of
`<dir>/openapi.yaml` at the head and at the base (a file absent at the base is the empty set; one that cannot be parsed there is a finding).
Every member of M(head) minus M(base) needs a data row (`<operationId>.<code>`, operation_error_code, openapi.yaml) in
`<dir>/required-identifiers.tsv` at the head whose requirement cell matches ^INV-REQ-[0-9]+$. Members of M(base) are the baseline.

Owner ratchet (INV-T-18955): every requirement cell a row had at the base must still be a requirement cell of that row at the head
(adding a cell is allowed). Exemption: a line of `spectral/required-identifiers-owners.tsv` read from the base commit (never the working
tree), 7 tab-separated cells: table, identifier, kind, file, old_requirement, new_requirement, ruling (INV-Q-<number>). The line exempts
one old cell of one row; new_requirement must be a requirement cell of that row at the head. A malformed line, a duplicate (same table,
triple and old cell) or a line for a row that is not in the table at the base is a finding naming its line number.

Audited-operation completeness (INV-T-7643, INV-Q-7641 A; made unconditional by INV-T-7650), with and without --base:
H are the data lines of `catalogue/audited-operations.tsv` in the working tree (four tab-separated cells: listedName,
operationId, service, discriminator; the header line, blank lines, `#` lines and lines without four cells are not
data lines). For every line of H whose operationId is not `UNCONTRACTED`, `<service>/required-identifiers.tsv` must
hold a data row (operationId, operation, openapi.yaml), compared by equality after trimming. There is no baseline: B is
treated as empty, so no pair is exempt. With --base, the table present at the base commit and missing at the head is
still a finding; absent at both, no check.

    operation_response  `<operationId>.<STATUS>` (split at the first dot): under `paths`, an operation whose own
                `operationId` equals <operationId> has a `responses` key whose string value equals <STATUS> (the YAML
                key 202 and the quoted key '202' both read as 202; `default` is valid). Another operation's responses,
                `components.responses`, a description and a substring never count (INV-T-11736, INV-Q-11733 A)

Usage: check-required-identifiers.py [--base <sha>] [<root>]      (default root: the current directory)
Standard library plus PyYAML, as the other spectral checks.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

import yaml

KINDS = ("property", "operation", "enum_value", "query_parameter", "schema_property", "schema_enum_value", "operation_error_code", "operation_response", "root_property", "asyncapi_message", "asyncapi_operation")
SCOPED = {"schema_property": 2, "schema_enum_value": 3, "operation_error_code": 2, "operation_response": 2}
SCOPED_FORM = {"schema_property": "<Schema>.<property>", "schema_enum_value": "<Schema>.<property>.<MEMBER>", "operation_error_code": "<operationId>.<CODE>", "operation_response": "<operationId>.<STATUS>"}
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
    root_props = doc.get("properties") if isinstance(doc, dict) else None
    if isinstance(root_props, dict):
        found["root_property"].update(str(k) for k in root_props)
    paths = doc.get("paths") if isinstance(doc, dict) else None
    for item in (paths.values() if isinstance(paths, dict) else ()):
        for op in (item.values() if isinstance(item, dict) else ()):
            responses = op.get("responses") if isinstance(op, dict) else None
            if isinstance(op, dict) and isinstance(op.get("operationId"), str) and isinstance(responses, dict):
                found["operation_response"].update((op["operationId"], str(k)) for k in responses)
    if isinstance(doc, dict):
        messages = (doc.get("components") or {}).get("messages") if isinstance(doc.get("components"), dict) else None
        if isinstance(messages, dict):
            found["asyncapi_message"].update(str(k) for k in messages)
        ops = doc.get("operations")
        if isinstance(ops, dict):
            found["asyncapi_operation"].update(str(k) for k in ops)
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


RETIRED = "RETIRED"
RENAMES = "spectral/required-identifiers-renames.tsv"
RENAME_HEADER = ("table", "old_identifier", "old_kind", "old_file", "new_identifier", "new_kind", "new_file", "ruling")


def row_reqs(text: str) -> dict[tuple[str, str, str], set[str]]:
    """The requirement cells of every data row of a table, by its (identifier, kind, file) key."""
    out: dict[tuple[str, str, str], set[str]] = {}
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
            out.setdefault(tuple(c.strip() for c in cells[:3]), set()).add(cells[3].strip())
    return out


def rename_lines(text: str) -> list[tuple[int, list[str]]]:
    """(line number, trimmed cells) of every data line of the rename map; comments, blanks and the header are not data lines."""
    out: list[tuple[int, list[str]]] = []
    first = True
    for no, raw in enumerate(text.splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        cells = [c.strip() for c in raw.split("\t")]
        if first and tuple(cells) == RENAME_HEADER:
            first = False
            continue
        first = False
        out.append((no, cells))
    return out


def rename_findings(lines: list[tuple[int, list[str]]]) -> list[str]:
    """Map checks: each bad line is a finding naming its line number."""
    found: list[str] = []
    seen: dict[tuple[str, ...], int] = {}
    for no, c in lines:
        tag = f"{RENAMES}: map line {no}"
        if len(c) != 8 or not all(c):
            found.append(f"{tag}: needs exactly eight non-empty cells")
            continue
        marks = [x == RETIRED for x in c[4:7]]
        if any(marks) and not all(marks):
            found.append(f"{tag}: self-contradicting line: {RETIRED} must be in all of new_identifier, new_kind and new_file or in none")
        if all(marks):
            if not re.fullmatch(r"INV-Q-[0-9]+", c[7]):
                found.append(f"{tag}: the ruling cell {c[7]!r} of a retirement line is not an INV-Q-<number> key")
        elif not re.fullmatch(r"INV-[A-Z]+-[0-9]+", c[7]):
            found.append(f"{tag}: the ruling cell {c[7]!r} is not an INV-<KIND>-<number> key")
        if not re.fullmatch(r"[^/]+/required-identifiers\.tsv", c[0]) or c[0].split("/")[0] in (".", ".."):
            found.append(f"{tag}: the table cell {c[0]!r} is not of the form <dir>/required-identifiers.tsv")
        if c[1:4] == c[4:7]:
            found.append(f"{tag}: the old triple equals the new triple")
        key = tuple(c[:4])
        if key in seen:
            found.append(f"{tag}: the same table and old triple as map line {seen[key]}")
        else:
            seen[key] = no
    return found


def is_retirement(c: list[str]) -> bool:
    return len(c) == 8 and all(c) and all(x == RETIRED for x in c[4:7])


def valid_retirement(c: list[str]) -> bool:
    return (is_retirement(c) and re.fullmatch(r"INV-Q-[0-9]+", c[7]) is not None
            and re.fullmatch(r"[^/]+/required-identifiers\.tsv", c[0]) is not None and c[0].split("/")[0] not in (".", ".."))


def absent_at_head(root: Path, table: str, ident: str, kind: str, file: str) -> bool:
    """INV-Q-17427 A: the element of a retired row is really absent from the file at the head; anything unreadable is not absent."""
    base = (root / table).parent.resolve()
    target = (base / file).resolve()
    if base not in target.parents or not target.is_file() or kind not in KINDS:
        return False
    try:
        loaded = collect(yaml.safe_load(target.read_text()))
    except yaml.YAMLError:
        return False
    if kind in SCOPED:
        parts = ident.split(".", SCOPED[kind] - 1)
        return len(parts) == SCOPED[kind] and all(parts) and tuple(parts) not in loaded[kind]
    return ident not in loaded[kind]


def valid_rename(c: list[str]) -> bool:
    return (len(c) == 8 and all(c) and not any(x == RETIRED for x in c[4:7]) and re.fullmatch(r"INV-[A-Z]+-[0-9]+", c[7]) is not None
            and re.fullmatch(r"[^/]+/required-identifiers\.tsv", c[0]) is not None and c[0].split("/")[0] not in (".", "..")
            and c[1:4] != c[4:7])


OWNERS = "spectral/required-identifiers-owners.tsv"
OWNER_HEADER = ("table", "identifier", "kind", "file", "old_requirement", "new_requirement", "ruling")
INV_REQ = re.compile(r"INV-REQ-[0-9]+")


def owner_lines(text: str) -> list[tuple[int, list[str]]]:
    """(line number, trimmed cells) of every data line of the owner map; comments, blanks and the header are not data lines."""
    out: list[tuple[int, list[str]]] = []
    first = True
    for no, raw in enumerate(text.splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        cells = [c.strip() for c in raw.split("\t")]
        if first and tuple(cells) == OWNER_HEADER:
            first = False
            continue
        first = False
        out.append((no, cells))
    return out


def owner_findings(lines: list[tuple[int, list[str]]], base_rows: dict[str, dict]) -> list[str]:
    """Map checks: each bad line is a finding naming its line number. base_rows: table name -> row_reqs of the table at the base."""
    found: list[str] = []
    seen: dict[tuple[str, ...], int] = {}
    for no, c in lines:
        tag = f"{OWNERS}: map line {no}"
        if len(c) != 7 or not all(c):
            found.append(f"{tag}: needs exactly seven non-empty cells")
            continue
        if not INV_REQ.fullmatch(c[5]):
            found.append(f"{tag}: new_requirement {c[5]!r} does not match INV-REQ-<number>")
        if not re.fullmatch(r"INV-Q-[0-9]+", c[6]):
            found.append(f"{tag}: the ruling cell {c[6]!r} is not an INV-Q-<number> key")
        if c[4] == c[5]:
            found.append(f"{tag}: old_requirement equals new_requirement")
        if tuple(c[1:4]) not in base_rows.get(c[0], {}):
            found.append(f"{tag}: ({c[1]}, {c[2]}, {c[3]}) is not a row of table {c[0]!r} at the base")
        key = tuple(c[:5])
        if key in seen:
            found.append(f"{tag}: the same table, triple and old cell as map line {seen[key]}")
        else:
            seen[key] = no
    return found


def valid_owner(c: list[str], key: tuple[str, str, str], table: str, old: str, head_cells: set[str]) -> bool:
    return (len(c) == 7 and all(c) and c[0] == table and tuple(c[1:4]) == key and c[4] == old and c[4] != c[5]
            and INV_REQ.fullmatch(c[5]) is not None and c[5] in head_cells and re.fullmatch(r"INV-Q-[0-9]+", c[6]) is not None)


def error_members(text: str) -> set[tuple[str, str]] | str:
    """The (operationId, code) members of an openapi.yaml text, or the parse error text."""
    try:
        return collect(yaml.safe_load(text))["operation_error_code"]
    except yaml.YAMLError as e:
        return f"cannot be parsed: {str(e).splitlines()[0]}"


def check_completeness(root: Path, sha: str, git) -> list[str]:
    """INV-T-18955: every x-error-codes member new since the base needs an operation_error_code row with an INV-REQ- cell."""
    findings: list[str] = []
    for spec in sorted(root.glob("*/openapi.yaml")):
        d = spec.parent.name
        name = f"{d}/openapi.yaml"
        head = error_members(spec.read_text())
        if isinstance(head, str):
            findings.append(f"{name}: {head}")
            continue
        base: set[tuple[str, str]] = set()
        if git("cat-file", "-e", f"{sha}:{name}").returncode == 0:
            shown = git("show", f"{sha}:{name}")
            parsed = error_members(shown.stdout) if shown.returncode == 0 else f"cannot be read: {shown.stderr.strip()}"
            if isinstance(parsed, str):
                findings.append(f"{name}: at {sha} it {parsed}")
                continue
            base = parsed
        table = root / d / "required-identifiers.tsv"
        reqs = row_reqs(table.read_text()) if table.is_file() else {}
        for op, code in sorted(head - base):
            cells = reqs.get((f"{op}.{code}", "operation_error_code", "openapi.yaml"), set())
            if not any(INV_REQ.fullmatch(x) for x in cells):
                findings.append(f"x-error-codes member '{op}.{code}' of {name} is new since {sha} and has no operation_error_code row with an INV-REQ-<number> requirement cell")
    return findings


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
    mapped = git("show", f"{sha}:{RENAMES}") if RENAMES in listing.stdout.splitlines() else None
    lines = rename_lines(mapped.stdout) if mapped is not None and mapped.returncode == 0 else []
    findings += rename_findings(lines)
    base_rows: dict[str, dict] = {}
    for name in sorted(n for n in listing.stdout.splitlines() if re.fullmatch(r"[^/]+/required-identifiers\.tsv", n)):
        head = root / name
        if not head.is_file():
            findings.append(f"{name}: exists at {sha} and is missing at the head")
            continue
        before = git("show", f"{sha}:{name}")
        if before.returncode != 0:
            findings.append(f"{name}: cannot read it at {sha}: {before.stderr.strip()}")
            continue
        head_reqs = row_reqs(head.read_text())
        base_reqs = row_reqs(before.stdout)
        base_rows[name] = base_reqs
        owners = git("show", f"{sha}:{OWNERS}") if OWNERS in listing.stdout.splitlines() else None
        olines = owner_lines(owners.stdout) if owners is not None and owners.returncode == 0 else []
        for key in sorted(set(base_reqs) & set(head_reqs)):
            for old in sorted(base_reqs[key] - head_reqs[key]):
                if any(valid_owner(c, key, name, old, head_reqs[key]) for _, c in olines):
                    continue
                findings.append(f"requirement cell changed since {sha}: '{key[0]}' ({key[1]}, {key[2]}) had '{old}'")
        for key in sorted(set(base_reqs) - set(head_reqs)):
            ident, kind, file = key
            hits = [c for _, c in lines if len(c) == 8 and all(c) and c[0] == name and tuple(c[1:4]) == key]
            if len(hits) == 1 and valid_rename(hits[0]) and base_reqs[key] <= head_reqs.get(tuple(hits[0][4:7]), set()):
                continue
            if len(hits) == 1 and valid_retirement(hits[0]) and key not in head_reqs and absent_at_head(root, name, ident, kind, file):
                continue
            findings.append(f"{name}: row removed since {sha}: {ident!r} ({kind}, {file})")
    owners = git("show", f"{sha}:{OWNERS}") if OWNERS in listing.stdout.splitlines() else None
    findings += owner_findings(owner_lines(owners.stdout) if owners is not None and owners.returncode == 0 else [], base_rows)
    findings += check_completeness(root, sha, git)
    return findings


AUDITED = "catalogue/audited-operations.tsv"


def audited_lines(text: str) -> list[tuple[int, str, str]]:
    """(line number, operationId, service) of every contracted data line of the audited-operations table."""
    out: list[tuple[int, str, str]] = []
    for no, raw in enumerate(text.splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        cells = [c.strip() for c in raw.split("\t")]
        if len(cells) != 4 or cells[0] == "listedName":
            continue
        if cells[1] != "UNCONTRACTED":
            out.append((no, cells[1], cells[2]))
    return out


def check_audited(root: Path, sha: str | None) -> list[str]:
    """INV-Q-7641 A, INV-T-7650: every contracted audited operation needs its operation row in its service table (no baseline)."""
    def git(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)

    head = root / AUDITED
    before = git("show", f"{sha}:{AUDITED}") if sha is not None and git("cat-file", "-e", f"{sha}:{AUDITED}").returncode == 0 else None
    if not head.is_file():
        return [f"{AUDITED}: exists at {sha} and is missing at the head"] if before is not None and before.returncode == 0 else []
    findings: list[str] = []
    rows: dict[str, set | None] = {}
    for no, op, svc in audited_lines(head.read_text()):
        if svc not in rows:
            table = root / svc / "required-identifiers.tsv"
            ok = re.fullmatch(r"[^/]+", svc) and svc not in (".", "..") and table.is_file()
            rows[svc] = row_keys(table.read_text()) if ok else None
        keys = rows[svc]
        if keys is not None and (op, "operation", "openapi.yaml") in keys:
            continue
        tail = "" if keys is not None else f"; {svc}/required-identifiers.tsv does not exist"
        findings.append(f"{root / AUDITED}:{no}: audited operation '{op}' of service '{svc}' has no row ({op}, operation, openapi.yaml) in {svc}/required-identifiers.tsv{tail}")
    return findings


def git_ok(root: Path, sha: str) -> bool:
    """True when the base is a usable commit (check_base reports the bad ones)."""
    if not sha.strip() or sha.startswith("-"):
        return False
    return subprocess.run(["git", "-C", str(root), "cat-file", "-e", f"{sha}^{{commit}}"], capture_output=True).returncode == 0


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
    if base is None or git_ok(root, base):
        findings += check_audited(root, base)
    if findings:
        print("gst-ci: required-identifiers FAIL")
        for f in findings:
            print(f"  - {f}")
        return 1
    print(f"gst-ci: required-identifiers OK — {len(tables)} table(s): {', '.join(str(t) for t in tables)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
