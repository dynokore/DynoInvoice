#!/usr/bin/env python3
"""spectral/check-request-enums.py — INV-T-25007 (INV-AC-25006; owner ruling 2026-11-18, option A)

Fails when a request-body enumeration carries the key `x-extensible-enum` (any value). A request
enumeration is closed (API-09, OWASP ASVS V5.1). A finding is a schema node that has an `enum` list and
the key `x-extensible-enum` (whole-string equality, so `x-extensible-enums` is another key) and is
reachable from the `requestBody` of any operation of an openapi.yaml: inline, through `#/components/...`
references, through relative-file `$ref`s (read whole), allOf, oneOf, anyOf, items, properties and
additionalProperties. A schema also reached by a response is still a request schema (deny by default).
Parameters and responses are not checked.

Usage: check-request-enums.py <service-dir> [...] | --discover ROOT
`--discover ROOT` checks every direct subdirectory of ROOT that holds an openapi.yaml (sorted by name);
none found exits 1. Exit 1 on a finding, an unreadable file or no file found. Each finding names the
file, the schema and the property path. Standard library plus PyYAML.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

MARKER = "x-extensible-enum"


def load(path: Path):
    text = path.read_text()
    return json.loads(text) if path.suffix == ".json" else yaml.safe_load(text)


class Walk:
    def __init__(self) -> None:
        self.findings: list[str] = []
        self.seen: set[tuple[str, str]] = set()
        self.docs: dict[Path, object] = {}

    def doc(self, path: Path):
        if path not in self.docs:
            self.docs[path] = load(path)
        return self.docs[path]

    def follow(self, ref: str, file: Path, schema: str, where: str) -> None:
        target, _, frag = ref.partition("#")
        try:
            path = file if not target else file.parent / target
            node = self.doc(path)
            for part in [p for p in frag.split("/") if p]:
                node = node[part.replace("~1", "/").replace("~0", "~")]
        except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError) as e:
            self.findings.append(f"{file}: {schema}: {where}: cannot resolve $ref '{ref}': {e}")
            return
        key = (str(path), frag)
        if key in self.seen:
            return
        self.seen.add(key)
        parts = [p for p in frag.split("/") if p]
        name = f"components.schemas.{parts[2]}" if len(parts) == 3 and parts[:2] == ["components", "schemas"] \
            else (f"{path.name}#{frag}" if frag else path.name)
        self.walk(node, path, name, "")

    def walk(self, node, file: Path, schema: str, where: str) -> None:
        if isinstance(node, list):
            for i, item in enumerate(node):
                self.walk(item, file, schema, f"{where}[{i}]")
            return
        if not isinstance(node, dict):
            return
        if isinstance(node.get("enum"), list) and MARKER in node:
            self.findings.append(f"{file}: {schema}: {where or '(schema root)'}: request enumeration "
                                 f"carries {MARKER}")
        ref = node.get("$ref")
        if isinstance(ref, str):
            self.follow(ref, file, schema, where)
        for key, value in node.items():
            if key == "$ref":
                continue
            if key == "properties" and isinstance(value, dict):
                for pname, pnode in value.items():
                    self.walk(pnode, file, schema, f"{where}.{pname}" if where else str(pname))
            elif key in ("items", "additionalProperties"):
                self.walk(value, file, schema, f"{where}.{key}" if where else key)
            else:
                self.walk(value, file, schema, where)

    def run(self, openapi: Path) -> None:
        doc = self.doc(openapi)
        for pth, item in (doc.get("paths") or {}).items():
            if not isinstance(item, dict):
                continue
            for method, op in item.items():
                if isinstance(op, dict) and op.get("requestBody") is not None:  # no operationId needed
                    self.walk(op["requestBody"], openapi, f"{pth} {method} requestBody", "")


def findings_for(d: Path) -> list[str]:
    openapi = d / "openapi.yaml"
    if not openapi.is_file():
        return [f"{d}: cannot read a contract file: {openapi} is missing"]
    w = Walk()
    try:
        w.run(openapi)
    except (OSError, ValueError, yaml.YAMLError, AttributeError, KeyError, TypeError) as e:
        w.findings.append(f"{d}: cannot read a contract file: {e}")
    return w.findings


def main(argv: list[str]) -> int:
    args = argv[1:]
    if args[:1] == ["--discover"]:
        if len(args) != 2:
            print("gst-ci: FAIL --discover needs exactly one ROOT")
            return 1
        root = Path(args[1])
        dirs = sorted((d for d in root.iterdir() if d.is_dir() and (d / "openapi.yaml").is_file()),
                      key=lambda d: d.name) if root.is_dir() else []
        if not dirs:
            print(f"gst-ci: FAIL no openapi.yaml found under {root}")
            return 1
    elif args and not any(a.startswith("--") for a in args):
        dirs = [Path(a) for a in args]
    else:
        print(__doc__)
        return 1
    findings = [f for d in dirs for f in findings_for(d)]
    for f in findings:
        print(f"gst-ci: FAIL {f}")
    if findings:
        return 1
    print(f"gst-ci: request-enum-closed OK — {', '.join(str(d) for d in dirs)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
