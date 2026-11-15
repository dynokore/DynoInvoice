#!/usr/bin/env python3
"""spectral/check-scope-exclusions.py — INV-T-13260 (INV-AC-13258, INV-AC-13259; INV-REQ-2562, INV-REQ-2564).

No contract operation or message may mention a GST return, filing, dunning or payment matching
unless a closed list allows it. Reads <root>/spectral/scope-exclusion-terms.tsv (requirement, term)
and <root>/spectral/scope-exclusion-allowed.tsv (kind, directory, name, requirement).

An item is the operationId of each operation of <root>/*/openapi.yaml (kind operation) or a key of
components.messages of <root>/*/asyncapi.yaml (kind message). Its tokens are its name and, for an
operation, its path, each split at lower-to-upper case changes (a digit counts as lower) and at
every character that is not a letter or digit, then lower-cased. An item is flagged when one token
equals a term. Exits 1, naming the file and the item, when:

  (a) a flagged item is not a row of the allowed table (kind, directory, name, by equality);
  (b) a row names an item that does not exist or is not flagged;
  (c) an operation has no operationId;
  (d) a table has another header, a row with another number of fields, a duplicate row, an empty
      terms table, a requirement that is not INV-REQ- and digits, or a term that is not lower-case
      letters and digits;
  (e) a YAML or TSV file is unreadable, or no openapi.yaml is found.

Prints `scope-exclusion-check: N flagged, N allowed` and exits 0 only when nothing fails.
A term check is a tripwire: an operation with another name that prepares a return stays a review duty.

Usage: check-scope-exclusions.py <root>   (needs python3 and PyYAML)
"""
import re
import sys
from pathlib import Path

import yaml

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
TERMS_HEADER = ["requirement", "term"]
ALLOWED_HEADER = ["kind", "directory", "name", "requirement"]
REQ_RE = re.compile(r"INV-REQ-[0-9]+")
TERM_RE = re.compile(r"[a-z0-9]+")
SPLIT_RE = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+")


def tokens(text: str) -> set:
    return {t.lower() for t in SPLIT_RE.split(text) if t}


def read_tsv(path: Path, header: list, errors: list) -> list:
    """The data rows of the TSV as tuples; problems go to errors."""
    rel = f"spectral/{path.name}"
    try:
        lines = path.read_text(encoding="utf-8").split("\n")
    except (OSError, UnicodeDecodeError) as ex:
        errors.append(f"{rel}: (e) unreadable: {ex}")
        return []
    if lines and lines[-1] == "":
        lines.pop()
    if not lines or lines[0].split("\t") != header:
        errors.append(f"{rel}: (d) the header line must be {chr(9).join(header)}")
        return []
    rows, seen = [], set()
    for n, line in enumerate(lines[1:], 2):
        fields = tuple(line.split("\t"))
        if len(fields) != len(header):
            errors.append(f"{rel}: (d) line {n} has {len(fields)} fields, expected {len(header)}")
        elif fields in seen:
            errors.append(f"{rel}: (d) line {n} is a duplicate row")
        else:
            seen.add(fields)
            rows.append((n,) + fields)
    return rows


def load_yaml(f: Path, label: str, errors: list):
    try:
        return yaml.safe_load(f.read_text(encoding="utf-8")), True
    except (yaml.YAMLError, OSError, UnicodeDecodeError) as ex:
        msg = str(ex).splitlines()[0] if str(ex) else repr(ex)
        errors.append(f"{label}: (e) unreadable YAML: {msg}")
        return None, False


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = Path(argv[1])
    errors = []

    terms = set()
    term_rows = read_tsv(root / "spectral" / "scope-exclusion-terms.tsv", TERMS_HEADER, errors)
    for n, req, term in term_rows:
        ok = True
        if not REQ_RE.fullmatch(req):
            errors.append(f"spectral/scope-exclusion-terms.tsv: (d) line {n} requirement {req!r} is not INV-REQ- and digits")
            ok = False
        if not TERM_RE.fullmatch(term):
            errors.append(f"spectral/scope-exclusion-terms.tsv: (d) line {n} term {term!r} is not lower-case letters and digits")
            ok = False
        if ok:
            terms.add(term)
    if not term_rows and not any("terms.tsv" in e for e in errors):
        errors.append("spectral/scope-exclusion-terms.tsv: (d) the terms table is empty")

    allowed = set()
    for n, kind, d, name, req in read_tsv(root / "spectral" / "scope-exclusion-allowed.tsv", ALLOWED_HEADER, errors):
        if not REQ_RE.fullmatch(req):
            errors.append(f"spectral/scope-exclusion-allowed.tsv: (d) line {n} requirement {req!r} is not INV-REQ- and digits")
        allowed.add((kind, d, name))

    items = set()    # (kind, directory, name)
    flagged = set()
    openapi = sorted(root.glob("*/openapi.yaml"))
    if not openapi:
        errors.append("no */openapi.yaml found under the root (e)")
    for f in openapi:
        d = f.parent.name
        doc, ok = load_yaml(f, f"{d}/openapi.yaml", errors)
        paths = doc.get("paths") if ok and isinstance(doc, dict) else None
        if not isinstance(paths, dict):
            continue
        for path, item in paths.items():
            if not isinstance(item, dict):
                continue
            for m in METHODS:
                op = item.get(m)
                if not isinstance(op, dict):
                    continue
                oid = op.get("operationId")
                if not isinstance(oid, str) or not oid:
                    errors.append(f"{d}/openapi.yaml: {m.upper()} {path}: (c) operation has no operationId")
                    continue
                key = ("operation", d, oid)
                items.add(key)
                if (tokens(oid) | tokens(str(path))) & terms:
                    flagged.add(key)
    for f in sorted(root.glob("*/asyncapi.yaml")):
        d = f.parent.name
        doc, ok = load_yaml(f, f"{d}/asyncapi.yaml", errors)
        comps = doc.get("components") if ok and isinstance(doc, dict) else None
        msgs = comps.get("messages") if isinstance(comps, dict) else None
        if not isinstance(msgs, dict):
            continue
        for name in msgs:
            key = ("message", d, str(name))
            items.add(key)
            if tokens(str(name)) & terms:
                flagged.add(key)

    def where(k):
        return f"{k[1]}/{'openapi' if k[0] == 'operation' else 'asyncapi'}.yaml: {k[0]} {k[2]}"

    for k in sorted(flagged - allowed):
        errors.append(f"{where(k)}: (a) mentions a scope-excluded term and is not a row of scope-exclusion-allowed.tsv")
    for k in sorted(allowed - items):
        errors.append(f"{k[1]}: {k[0]} {k[2]}: (b) row of scope-exclusion-allowed.tsv names an item that does not exist")
    for k in sorted((allowed & items) - flagged):
        errors.append(f"{where(k)}: (b) row of scope-exclusion-allowed.tsv names an item that is not flagged")

    if errors:
        print("scope-exclusion-check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"scope-exclusion-check: {len(flagged)} flagged, {len(allowed)} allowed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
