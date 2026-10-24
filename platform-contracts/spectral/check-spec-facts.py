#!/usr/bin/env python3
"""spectral/check-spec-facts.py — INV-T-9614 (INV-Q-9610 A): the spec-facts gate.

python3 spectral/check-spec-facts.py [--base <sha>] [<root>]

Reads every <root>/*/spec-facts.tsv. A table has the header file, pointer, relation, value, requirement
(tab separated, five cells; the header, blank lines and lines starting with '#' are not rows).
  file      a YAML or JSON file under the table's own directory
  pointer   an RFC 6901 JSON pointer into the parsed document
  relation  equals   the node equals the JSON text in value (same type, list order and keys; 1 is not true, 60 is not "60")
            contains the node is a list with a member equal to the JSON text in value
            absent   no node at the pointer; value is the single character '-'
With --base <sha>, a data row (keyed by the trimmed file, pointer, relation and value) of a table that exists at
<sha> must still be a data row of the same table at the head; there is no exemption for a retired row.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

import yaml

HEADER = ("file", "pointer", "relation", "value", "requirement")
RELATIONS = ("equals", "contains", "absent")
MISSING = object()


class Bad(Exception):
    pass


def data_rows(text: str):
    """(line number, cells) of every non-blank, non-comment line that is not the header."""
    first = True
    for no, raw in enumerate(text.splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        cells = raw.split("\t")
        if first and tuple(c.strip() for c in cells) == HEADER:
            first = False
            continue
        first = False
        yield no, cells


def strict_json(text: str):
    def refuse(token):
        raise ValueError(f"{token} is not JSON")
    return json.loads(text, parse_constant=refuse)


def same(a, b) -> bool:
    if isinstance(a, bool) or isinstance(b, bool):
        return isinstance(a, bool) and isinstance(b, bool) and a == b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return a == b
    if isinstance(a, str) and isinstance(b, str):
        return a == b
    if a is None or b is None:
        return a is None and b is None
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    if isinstance(a, dict) and isinstance(b, dict):
        ka = {str(k): v for k, v in a.items()}
        kb = {str(k): v for k, v in b.items()}
        return ka.keys() == kb.keys() and all(same(ka[k], kb[k]) for k in ka)
    return False


def tokens(pointer: str) -> list[str]:
    if not pointer.startswith("/"):
        raise Bad("the pointer does not start with '/'")
    out = []
    for raw in pointer[1:].split("/"):
        if re.search(r"~(?![01])", raw):
            raise Bad(f"the pointer has a bad escape in {raw!r} ('~' must be '~0' or '~1')")
        out.append(raw.replace("~1", "/").replace("~0", "~"))
    return out


def resolve(doc, toks: list[str]):
    node = doc
    for t in toks:
        if isinstance(node, dict):
            hit = [k for k in node if not isinstance(k, bool) and str(k) == t]
            if not hit:
                return MISSING
            node = node[hit[0]]
        elif isinstance(node, list):
            if not re.fullmatch(r"0|[1-9][0-9]*", t) or int(t) >= len(node):
                return MISSING
            node = node[int(t)]
        else:
            return MISSING
    return node


def load(table_dir: Path, name: str, cache: dict):
    target = (table_dir / name).resolve()
    base = table_dir.resolve()
    if Path(name).is_absolute() or base not in target.parents:
        raise Bad(f"the file {name!r} leaves the directory {table_dir}")
    if not target.is_file():
        raise Bad(f"the file {name!r} does not exist under {table_dir}")
    if target not in cache:
        try:
            text = target.read_text()
            cache[target] = json.loads(text) if target.suffix == ".json" else yaml.safe_load(text)
        except Exception as e:  # unparseable
            raise Bad(f"the file {name!r} cannot be parsed: {str(e).splitlines()[0] if str(e) else type(e).__name__}")
    return cache[target]


def check_table(table: Path, cache: dict) -> list[str]:
    findings: list[str] = []
    seen: set[tuple[str, str, str]] = set()
    rows = 0
    for no, cells in data_rows(table.read_text()):
        rows += 1
        where = f"{table}: row {no}"
        if len(cells) != 5 or not all(c.strip() for c in cells):
            findings.append(f"{where}: a row needs five non-empty cells (file, pointer, relation, value, requirement), got {len(cells)}")
            continue
        file, pointer, relation, value, req = (c.strip() for c in cells)
        tag = f"{where}, pointer {pointer}, requirement {req}"
        if relation not in RELATIONS:
            findings.append(f"{tag}: relation {relation!r} is not one of equals, contains, absent")
            continue
        expected = None
        if relation == "absent":
            if value != "-":
                findings.append(f"{tag}: the value of an absent row must be '-', got {value!r}")
                continue
        else:
            try:
                expected = strict_json(value)
            except ValueError:
                findings.append(f"{tag}: the value {value!r} is not valid JSON")
                continue
        key = (file, pointer, relation)
        if key in seen:
            findings.append(f"{tag}: duplicate row ({file}, {pointer}, {relation})")
            continue
        seen.add(key)
        try:
            toks = tokens(pointer)
            node = resolve(load(table.parent, file, cache), toks)
        except Bad as e:
            findings.append(f"{tag}: {e}")
            continue
        if relation == "absent":
            if node is not MISSING:
                findings.append(f"{tag}: the node is present in {file} but the row says absent")
        elif node is MISSING:
            findings.append(f"{tag}: the node is missing in {file}")
        elif relation == "equals":
            if not same(node, expected):
                findings.append(f"{tag}: the node in {file} is {json.dumps(node, default=str)}, the row says {value}")
        else:
            if not isinstance(node, list):
                findings.append(f"{tag}: the node in {file} is not a list, so it cannot contain {value}")
            elif not any(same(m, expected) for m in node):
                findings.append(f"{tag}: the list in {file} has no member {value}")
    if rows == 0:
        findings.append(f"{table}: the table has no rows")
    return findings


def row_keys(text: str) -> set[tuple[str, str, str, str]]:
    return {tuple(c.strip() for c in cells[:4]) for _, cells in data_rows(text) if len(cells) == 5 and all(c.strip() for c in cells)}


def git_ok(root: Path, sha: str) -> bool:
    if not sha.strip() or sha.startswith("-"):
        return False
    return subprocess.run(["git", "-C", str(root), "cat-file", "-e", f"{sha}^{{commit}}"], capture_output=True).returncode == 0


def check_base(root: Path, sha: str) -> list[str]:
    if not sha.strip() or sha.startswith("-"):
        return [f"--base needs a commit sha, got {sha!r}"]
    if not git_ok(root, sha):
        return [f"--base {sha!r} is not a commit of the repository at {root}"]

    def git(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)

    listing = git("ls-tree", "-r", "--name-only", sha)
    if listing.returncode != 0:
        return [f"--base {sha!r}: git ls-tree failed: {listing.stderr.strip()}"]
    findings: list[str] = []
    for name in sorted(n for n in listing.stdout.splitlines() if re.fullmatch(r"[^/]+/spec-facts\.tsv", n)):
        head = root / name
        if not head.is_file():
            findings.append(f"{name}: exists at {sha} and is missing at the head")
            continue
        before = git("show", f"{sha}:{name}")
        if before.returncode != 0:
            findings.append(f"{name}: cannot read it at {sha}: {before.stderr.strip()}")
            continue
        now = row_keys(head.read_text())
        for file, pointer, relation, value in sorted(row_keys(before.stdout) - now):
            findings.append(f"{name}: row removed since {sha}: file {file}, pointer {pointer}, relation {relation}, value {value}")
    return findings


def main(argv: list[str]) -> int:
    args = argv[1:]
    base = None
    if args[:1] == ["--base"]:
        if len(args) < 2:
            print("gst-ci: spec-facts FAIL — --base needs a commit sha")
            return 1
        base, args = args[1], args[2:]
    root = Path(args[0]) if args else Path(".")
    tables = sorted(root.glob("*/spec-facts.tsv"))
    findings: list[str] = []
    if not tables:
        findings.append(f"no */spec-facts.tsv found under {root}")
    cache: dict = {}
    for table in tables:
        findings += check_table(table, cache)
    if base is not None:
        findings += check_base(root, base)
    if findings:
        print("gst-ci: spec-facts FAIL")
        for f in findings:
            print(f"  - {f}")
        return 1
    for table in tables:
        print(f"gst-ci: spec-facts OK — {table}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
