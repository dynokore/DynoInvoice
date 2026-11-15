#!/usr/bin/env python3
"""spectral/filter-asyncapi-exceptions.py <exceptions-file>

Reads the JSON array printed by `asyncapi diff -t breaking -f json` on standard input and exits 1,
listing every element whose canonical text (json.dumps, sorted keys, separators "," and ":") does not
equal the canonical text of an entry of the exception file. Exit 0 when every element is listed or the
array is empty.

Exception file (spectral/asyncapi-exceptions.txt): one JSON object per line. A line is an entry only
when the line before it is a comment line "# ..." naming a ruling key (INV-Q-<n>, INV-T-<n>) or an
owner ruling date (YYYY-MM-DD). An uncommented entry, a line that is not a JSON object and a line with a
wildcard character are errors (exit 1). Matching is whole-entry equality, never a prefix or glob.
A listed entry that matches no change is ignored. Fail closed: any unparseable input exits 1.
"""
import json
import re
import sys

RULING = re.compile(r"\b(?:INV-[A-Z]+-\d+|\d{4}-\d{2}-\d{2})\b")
WILDCARDS = ("*", "?")


def canon(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def load_entries(path):
    entries, errors, prev = set(), [], ""
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    for no, raw in enumerate(lines, 1):
        line = raw.strip()
        if not line:
            prev = ""
            continue
        if line.startswith("#"):
            prev = line
            continue
        if any(w in line for w in WILDCARDS):
            errors.append(f"{path}:{no}: wildcard in entry")
        elif not (prev.startswith("# ") and RULING.search(prev)):
            errors.append(f"{path}:{no}: entry without a preceding '# ' comment naming a ruling key or date")
        else:
            try:
                obj = json.loads(line)
            except ValueError as exc:
                errors.append(f"{path}:{no}: not valid JSON ({exc})")
            else:
                if isinstance(obj, dict):
                    entries.add(canon(obj))
                else:
                    errors.append(f"{path}:{no}: entry is not a JSON object")
        prev = ""
    return entries, errors


def main(argv):
    if len(argv) != 2:
        print("usage: filter-asyncapi-exceptions.py <exceptions-file>", file=sys.stderr)
        return 1
    try:
        entries, errors = load_entries(argv[1])
    except OSError as exc:
        print(f"gst-ci: cannot read exception file: {exc}", file=sys.stderr)
        return 1
    if errors:
        for e in errors:
            print(f"gst-ci: invalid exception file: {e}")
        return 1
    try:
        data = json.loads(sys.stdin.read())
    except ValueError as exc:
        print(f"gst-ci: breaking-change output is not JSON: {exc}")
        return 1
    if not isinstance(data, list):
        print("gst-ci: breaking-change output is not a JSON array")
        return 1
    bad = 0
    for el in data:
        text = canon(el)
        if text not in entries:
            print(f"gst-ci: unlisted breaking AsyncAPI change: {text}")
            bad = 1
    return bad


if __name__ == "__main__":
    sys.exit(main(sys.argv))
