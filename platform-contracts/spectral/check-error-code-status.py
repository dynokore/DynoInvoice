#!/usr/bin/env python3
"""spectral/check-error-code-status.py — INV-T-23085 (INV-REQ-827; shift-left contract lint)

Every `x-error-codes` member of an operation must be answered by a response whose key is the HTTP
status of its catalogue code. Reads `<root>/catalogue/errors/error-codes.yaml`, every
`<root>/*/openapi.yaml` (one directory level, sorted), `<root>/spectral/error-code-status.tsv`
(header `code`, `status`, `source`) and `<root>/spectral/error-code-status-baseline.tsv`
(header `directory`, `operationId`, `code`, `reason`).

Rule. An operation is a mapping under `paths.<path>.<method>` with a non-empty string `operationId`.
For each member CODE of its `x-error-codes`, the status S of CODE is (1) the one distinct value among
the matches of `\\bHTTP ([1-5][0-9]{2})\\b` in the `condition` of the catalogue entry whose `code`
equals CODE (case-sensitive, untrimmed; the matches are a set, so a repeated identical match counts
once), or, when the condition has no match, (2) the `status` cell of the row of the status table for
CODE. The operation must have a `responses` key whose string value equals S (the YAML key 409 and
the quoted key '409' both read 409). `default`, `4XX`, `409 ` (trailing space), a response of another
operation and `components.responses` never count.

Baseline. A row (directory, operationId, code) is exempt only while that pair still fails for the
row's reason: `status_not_declared` (CODE has a status and the operation lacks the response) or
`no_status` (CODE has no status in either source). The file only shrinks; rows are never added.

Exit 1 on: an operation whose member has no response with its status; a member whose code has no
status in either source (and no baseline row); a condition with two or more different matches; a
status-table row for a code that is not a catalogue code, with a status that is not three digits
100 to 599, with a source not matching ^INV-(Q|REQ)-[0-9]+$, for a code whose condition has any
match (one source per code), or a second row for one code; a table or baseline header that is not
exactly its columns (tab separated), a missing table or baseline file; a baseline row with other
than four cells, with a reason other than the two names, a duplicate row, a row whose directory is
no contract directory, a row for an operationId or code that does not exist in that directory
("unknown baseline row"), a row whose pair now passes or fails for the other reason ("stale baseline
row"); a catalogue that is missing, unparseable, not of the shape `error_codes` non-empty list of
mappings with a non-empty string `code` and a string `condition`; no `*/openapi.yaml`; a contract
that cannot be parsed; an `x-error-codes` value that is not a list or a member that is not a string.
Exit 0 prints `gst-ci: error-code-status OK` with the numbers of members, operations, baseline rows.

Usage: check-error-code-status.py [<root>]      (default root: the current directory)
Standard library plus PyYAML, as the other spectral checks.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

STATUS_RE = re.compile(r"\bHTTP ([1-5][0-9]{2})\b")
SOURCE_RE = re.compile(r"^INV-(Q|REQ)-[0-9]+$")
STATUS_CELL_RE = re.compile(r"^[1-5][0-9]{2}$")
TABLE = "spectral/error-code-status.tsv"
BASELINE = "spectral/error-code-status-baseline.tsv"
CATALOGUE = "catalogue/errors/error-codes.yaml"
REASONS = ("status_not_declared", "no_status")


def load_catalogue(root: Path, findings: list[str]) -> dict[str, set[str]] | None:
    path = root / CATALOGUE
    if not path.is_file():
        findings.append(f"{CATALOGUE}: the catalogue does not exist")
        return None
    try:
        doc = yaml.safe_load(path.read_text())
    except (yaml.YAMLError, OSError) as e:
        findings.append(f"{CATALOGUE}: the catalogue cannot be parsed: {str(e).splitlines()[0]}")
        return None
    entries = doc.get("error_codes") if isinstance(doc, dict) else None
    if not isinstance(entries, list) or not entries:
        findings.append(f"{CATALOGUE}: the key error_codes must be a non-empty list")
        return None
    out: dict[str, set[str]] = {}
    ok = True
    for i, entry in enumerate(entries, 1):
        code = entry.get("code") if isinstance(entry, dict) else None
        if not isinstance(code, str) or not code:
            findings.append(f"{CATALOGUE}: entry {i} has no non-empty string code")
            ok = False
            continue
        cond = entry.get("condition")
        if not isinstance(cond, str):
            findings.append(f"{CATALOGUE}: entry {i} ({code}) has no string condition")
            ok = False
            continue
        found = set(STATUS_RE.findall(cond))
        if len(found) > 1:
            findings.append(f"{CATALOGUE}: code {code} has more than one different status in its condition: {sorted(found)}")
        out.setdefault(code, set()).update(found)
    return out if ok else None


def read_tsv(root: Path, rel: str, header: list[str], findings: list[str]) -> list[tuple[int, list[str]]] | None:
    path = root / rel
    if not path.is_file():
        findings.append(f"{rel}: the file does not exist")
        return None
    try:
        lines = path.read_text().split("\n")
    except OSError as e:
        findings.append(f"{rel}: cannot be read: {e}")
        return None
    if lines and lines[-1] == "":
        lines.pop()
    if not lines or lines[0].split("\t") != header:
        findings.append(f"{rel}: the header must be exactly {' <TAB> '.join(header)}")
        return None
    return [(n, ln.split("\t")) for n, ln in enumerate(lines[1:], 2) if ln != ""]


def load_table(root: Path, cat: dict[str, set[str]] | None, findings: list[str]) -> dict[str, str]:
    rows = read_tsv(root, TABLE, ["code", "status", "source"], findings)
    table: dict[str, str] = {}
    for n, cells in rows or []:
        where = f"{TABLE}:{n}"
        if len(cells) != 3:
            findings.append(f"{where}: a row needs exactly 3 cells")
            continue
        code, status, source = cells
        if cat is not None and code not in cat:
            findings.append(f"{where}: code '{code}' is not a code of the catalogue")
        elif cat is not None and cat[code]:
            findings.append(f"{where}: code '{code}' already has a status in its catalogue condition (one source per code)")
        if not STATUS_CELL_RE.match(status):
            findings.append(f"{where}: status '{status}' is not three digits 100 to 599")
        if not SOURCE_RE.match(source):
            findings.append(f"{where}: source '{source}' does not match ^INV-(Q|REQ)-[0-9]+$")
        if code in table:
            findings.append(f"{where}: second row for code '{code}'")
            continue
        table[code] = status
    return table


def operations(doc):
    paths = doc.get("paths") if isinstance(doc, dict) else None
    if not isinstance(paths, dict):
        return
    for item in paths.values():
        if not isinstance(item, dict):
            continue
        for op in item.values():
            if isinstance(op, dict) and isinstance(op.get("operationId"), str) and op["operationId"]:
                yield op


def evaluate(root: Path, cat, table, contracts, findings):
    """Return (members, operations, failing) with failing {(dir, op, code): reason}."""
    members = ops = 0
    failing: dict[tuple[str, str, str], str] = {}
    existing: set[tuple[str, str, str]] = set()
    opids: set[tuple[str, str]] = set()
    for c in contracts:
        name = c.relative_to(root).as_posix()
        directory = c.parent.name
        try:
            doc = yaml.safe_load(c.read_text())
        except (yaml.YAMLError, OSError) as e:
            findings.append(f"{name}: the contract cannot be parsed: {str(e).splitlines()[0]}")
            continue
        for op in operations(doc):
            ops += 1
            oid = op["operationId"]
            opids.add((directory, oid))
            if "x-error-codes" not in op:
                continue
            value = op["x-error-codes"]
            if not isinstance(value, list):
                findings.append(f"{name}: operation {oid}: x-error-codes is not a list")
                continue
            responses = op.get("responses")
            keys = {str(k) for k in responses if isinstance(k, (str, int)) and not isinstance(k, bool)} if isinstance(responses, dict) else set()
            for code in value:
                members += 1
                if not isinstance(code, str):
                    findings.append(f"{name}: operation {oid}: x-error-codes member {code!r} is not a string")
                    continue
                existing.add((directory, oid, code))
                if cat is None:
                    continue
                found = cat.get(code)
                if found is None:
                    continue  # not a catalogue code: check-error-code-catalogue.py owns that finding
                if len(found) > 1:
                    continue  # reported once on the catalogue entry
                status = next(iter(found)) if found else table.get(code)
                if status is None:
                    failing[(directory, oid, code)] = "no_status"
                elif status not in keys:
                    failing[(directory, oid, code)] = "status_not_declared"
    return members, ops, failing, existing, opids


def main(argv: list[str]) -> int:
    root = Path(argv[1] if len(argv) > 1 else ".")
    findings: list[str] = []
    cat = load_catalogue(root, findings)
    table = load_table(root, cat, findings)
    contracts = sorted(root.glob("*/openapi.yaml"))
    if not contracts:
        findings.append("no */openapi.yaml found")
    members, ops, failing, existing, opids = evaluate(root, cat, table, contracts, findings)
    dirs = {c.parent.name for c in contracts}

    rows = read_tsv(root, BASELINE, ["directory", "operationId", "code", "reason"], findings)
    base: dict[tuple[str, str, str], str] = {}
    for n, cells in rows or []:
        where = f"{BASELINE}:{n}"
        if len(cells) != 4:
            findings.append(f"{where}: a row needs exactly 4 cells")
            continue
        d, oid, code, reason = cells
        key = (d, oid, code)
        if reason not in REASONS:
            findings.append(f"{where}: reason '{reason}' is not one of {', '.join(REASONS)}")
            continue
        if key in base:
            findings.append(f"{where}: duplicate baseline row {d}/{oid} {code}")
            continue
        if d not in dirs:
            findings.append(f"{where}: directory '{d}' is not a contract directory")
            continue
        if (d, oid) not in opids or key not in existing:
            findings.append(f"{where}: unknown baseline row {d}/{oid} {code} (no such operation or member in that directory)")
            continue
        base[key] = reason
        if failing.get(key) != reason:
            findings.append(f"{where}: stale baseline row {d}/{oid} {code} (reason {reason}; it now {'fails as ' + failing[key] if key in failing else 'passes'})")

    for (d, oid, code), reason in sorted(failing.items()):
        if base.get((d, oid, code)) == reason:
            continue
        if reason == "no_status":
            findings.append(f"{d}/openapi.yaml: operation {oid}: code {code} has no status in the catalogue condition or in {TABLE}")
        else:
            status = next(iter(cat[code])) if cat and cat.get(code) else table.get(code)
            findings.append(f"{d}/openapi.yaml: operation {oid}: code {code} has status {status} but the operation has no response {status}")

    if findings:
        for f in findings:
            print(f"gst-ci: error-code-status FAIL — {f}")
        return 1
    print(f"gst-ci: error-code-status OK — {members} member(s), {ops} operation(s), {len(base)} baseline row(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
