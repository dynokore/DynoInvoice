#!/usr/bin/env python3
"""spectral/normalize-extensible-enum.py — INV-T-3491 gate fix (INV-Q-3569, API-09, CS-50).

API-09 marks every response enumeration extensible with `enum: [..]` beside
`x-extensible-enum: true`, and calls a new value additive. The pinned oasdiff
reads only its own list form, `x-extensible-enum: [..]` without `enum`, and
reports a value added to the API-09 form as response-property-enum-value-added
(ERR). Before the diff, run-oasdiff-gate.sh rewrites BOTH copies (base and
head) with this script, so that a schema carrying `enum` and
`x-extensible-enum: true` becomes `x-extensible-enum: <the enum list>`.

Only schemas carrying the marker change: an enum without `x-extensible-enum:
true` is left as it is, so a value added to it still fails the gate. A value
removed from a marked request enum still fails, and removing a response value
is not breaking for oasdiff in either form (checked on the pinned image).

Usage: normalize-extensible-enum.py <in.yaml> <out.yaml>   (in and out may be the same file)
Needs PyYAML.
"""
import sys

import yaml


def walk(node):
    if isinstance(node, dict):
        if node.get("x-extensible-enum") is True and isinstance(node.get("enum"), list):
            node["x-extensible-enum"] = node.pop("enum")
        for v in node.values():
            walk(v)
    elif isinstance(node, list):
        for v in node:
            walk(v)


def main(argv) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    with open(argv[1], encoding="utf-8") as f:
        doc = yaml.safe_load(f)
    walk(doc)
    with open(argv[2], "w", encoding="utf-8") as f:
        yaml.safe_dump(doc, f, sort_keys=False, allow_unicode=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
