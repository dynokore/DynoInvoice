#!/usr/bin/env python3
"""spectral/check-forbidden-properties.py — INV-T-4910

Fails when a secret leaves a service in a response or an event. A service directory holds
`forbidden-properties.tsv`: one property name per row (blank lines and lines starting with `#`
ignored). A property whose name equals a listed name, ignoring case, underscores and hyphens, must
not appear in any response schema or event schema of the directory:
  - openapi.yaml: a schema counts as a request schema only when it is reachable from a requestBody
    and from no response; every other schema under components.schemas, and every response schema,
    is checked (deny by default, so a schema reached by neither is checked too);
  - asyncapi.yaml and schemas/*.schema.json: every event schema is checked.
The request that carries the entered secret is allowed. A name that merely contains the listed name
(`passcodeAttempts`, `sealedPasscode`) is a different property and passes.

Exit 1 on: a missing or empty table, an unreadable contract file, or a finding. Every finding names
the file, the schema and the property.
Usage: check-forbidden-properties.py <service-dir> [<service-dir> ...]
Standard library plus PyYAML.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml


def norm(name: str) -> str:
    return re.sub(r"[_\-]", "", name).lower()


def load(path: Path):
    text = path.read_text()
    return json.loads(text) if path.suffix == ".json" else yaml.safe_load(text)


def prop_names(node, where: str, out: list[tuple[str, str]]) -> None:
    """Every key of every `properties` mapping under node (no $ref following)."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "properties" and isinstance(value, dict):
                out.extend((where, str(k)) for k in value)
            prop_names(value, where, out)
    elif isinstance(node, list):
        for item in node:
            prop_names(item, where, out)


def refs(node, acc: set[str]) -> None:
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str) and ref.startswith("#/components/schemas/"):
            acc.add(ref.rsplit("/", 1)[1])
        for value in node.values():
            refs(value, acc)
    elif isinstance(node, list):
        for item in node:
            refs(item, acc)


def closure(start: set[str], schemas: dict) -> set[str]:
    seen, todo = set(), list(start)
    while todo:
        name = todo.pop()
        if name in seen or name not in schemas:
            continue
        seen.add(name)
        more: set[str] = set()
        refs(schemas[name], more)
        todo.extend(more)
    return seen


def openapi_findings(path: Path, forbidden: set[str]) -> list[str]:
    doc = load(path)
    schemas = (doc.get("components") or {}).get("schemas") or {}
    req_direct: set[str] = set()
    resp_direct: set[str] = set()
    inline: list[tuple[str, str]] = []
    for pth, item in (doc.get("paths") or {}).items():
        for method, op in (item or {}).items():
            if not isinstance(op, dict) or "operationId" not in op:
                continue
            refs(op.get("requestBody"), req_direct)
            refs(op.get("responses"), resp_direct)
            prop_names(op.get("responses"), f"{pth} {method} response", inline)
    # components/responses are response schemas too
    comp_resp = (doc.get("components") or {}).get("responses") or {}
    refs(comp_resp, resp_direct)
    prop_names(comp_resp, "components.responses", inline)
    request_only = closure(req_direct, schemas) - closure(resp_direct, schemas)
    found = list(inline)
    for name, schema in schemas.items():
        if name not in request_only:
            prop_names(schema, f"components.schemas.{name}", found)
    return [f"{path}: {where}: property '{prop}' is forbidden in a response or event schema"
            for where, prop in found if norm(prop) in forbidden]


def event_findings(path: Path, forbidden: set[str]) -> list[str]:
    found: list[tuple[str, str]] = []
    prop_names(load(path), path.name, found)
    return [f"{path}: {where}: property '{prop}' is forbidden in a response or event schema"
            for where, prop in found if norm(prop) in forbidden]


def check_dir(d: Path) -> list[str]:
    table = d / "forbidden-properties.tsv"
    if not table.is_file():
        return [f"{d}: forbidden-properties.tsv is missing"]
    names = {norm(ln.split("\t")[0].strip()) for ln in table.read_text().splitlines()
             if ln.strip() and not ln.lstrip().startswith("#")}
    names.discard("")
    if not names:
        return [f"{table}: the table has no rows"]
    findings: list[str] = []
    try:
        if (d / "openapi.yaml").is_file():
            findings += openapi_findings(d / "openapi.yaml", names)
        for f in [d / "asyncapi.yaml", *sorted((d / "schemas").glob("*.json"))]:
            if f.is_file():
                findings += event_findings(f, names)
    except (OSError, ValueError, yaml.YAMLError, AttributeError) as e:
        findings.append(f"{d}: cannot read a contract file: {e}")
    return findings


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 1
    findings = [f for a in argv[1:] for f in check_dir(Path(a))]
    for f in findings:
        print(f"gst-ci: FAIL {f}")
    if findings:
        return 1
    print(f"gst-ci: forbidden-properties OK — {', '.join(argv[1:])}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
