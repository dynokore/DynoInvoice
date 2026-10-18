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

Option --requests (INV-T-6499, INV-REQ-804, INV-AC-1859, INV-AC-2030): checks the request side instead.
A directory holds `forbidden-request-properties.tsv` (header `kind`, `name`, `reserved_in`; tab
separated; blank lines and lines starting with `#` ignored; `reserved_in` may be empty; kind is
`property` or `enum_value`). A name equal, ignoring case, underscores and hyphens, to a listed name of
the row's kind, found as a property key or a parameter `name` (kind property) or as an `enum` member
(kind enum_value) in a request schema of `<dir>/openapi.yaml`, is a finding. A request schema is every
schema reachable from a requestBody or a parameters entry of any operation (inline, `#/components/...`
references, allOf, oneOf, anyOf, items, properties, relative-file `$ref`s read whole); a schema also
reached by a response is still a request schema (deny by default). A finding is excused only when the
row's `reserved_in` equals the enclosing `components.schemas` entry, the property is in no `required`
list of that schema and its `description` starts with `Reserved:` (for an enum member: the description
of the property that holds the enum). Exit 1 on: a missing table, a table with no rows, a row of any
other kind, an unreadable contract file, or a finding. Without the option the behaviour above is
unchanged.
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


REQ_TABLE = "forbidden-request-properties.tsv"
REQ_KINDS = ("property", "enum_value")
RESERVED_HINT = " (reserved item must be optional and start with Reserved:)"


def read_request_table(d: Path) -> tuple[list[tuple[str, str, str]], list[str]]:
    table = d / REQ_TABLE
    if not table.is_file():
        return [], [f"{d}: {REQ_TABLE} is missing"]
    lines = [ln for ln in table.read_text().splitlines() if ln.strip() and not ln.lstrip().startswith("#")]
    if not lines or [c.strip() for c in lines[0].split("\t")] != ["kind", "name", "reserved_in"]:
        return [], [f"{table}: the header must be kind, name, reserved_in (tab separated)"]
    rows, errors = [], []
    for ln in lines[1:]:
        cells = [c.strip() for c in ln.split("\t")]
        cells += [""] * (3 - len(cells))
        if len(cells) > 3 or cells[0] not in REQ_KINDS or not cells[1]:
            errors.append(f"{table}: bad row '{ln}' (kind must be one of {', '.join(REQ_KINDS)}; name is required)")
        else:
            rows.append((cells[0], norm(cells[1]), cells[2]))
    if not rows and not errors:
        errors.append(f"{table}: the table has no rows")
    return rows, errors


class RequestWalk:
    """Walks every schema reachable from the requests of one openapi.yaml."""

    def __init__(self, d: Path, rows: list[tuple[str, str, str]]):
        self.d = d
        self.rows = rows
        self.findings: list[str] = []
        self.seen: set[tuple[str, str]] = set()
        self.docs: dict[Path, object] = {}

    def doc(self, path: Path):
        if path not in self.docs:
            self.docs[path] = load(path)
        return self.docs[path]

    def check_name(self, file, schema, kind, name, entry, holder, required) -> None:
        """entry: the enclosing components.schemas name or None; holder: the node whose description counts."""
        hits = [r for r in self.rows if r[0] == kind and r[1] == norm(str(name))]
        if not hits:
            return
        by_name = any(r[2] and r[2] == entry for r in hits)
        desc = holder.get("description") if isinstance(holder, dict) else None
        if by_name and norm(str(name)) not in required and isinstance(desc, str) and desc.startswith("Reserved:"):
            return
        what = "property" if kind == "property" else "enum member"
        self.findings.append(f"{file}: {schema}: {what} '{name}' is forbidden in a request schema"
                             + (RESERVED_HINT if by_name else ""))

    def required_of(self, node) -> set[str]:
        out: set[str] = set()
        if isinstance(node, dict):
            req = node.get("required")
            if isinstance(req, list):
                out.update(norm(str(x)) for x in req)
            for v in node.values():
                out |= self.required_of(v)
        elif isinstance(node, list):
            for v in node:
                out |= self.required_of(v)
        return out

    def resolve(self, ref: str, file: Path):
        target, _, frag = ref.partition("#")
        path = file if not target else (file.parent / target)
        doc = self.doc(path)
        node = doc
        for part in [p for p in frag.split("/") if p]:
            node = node[part.replace("~1", "/").replace("~0", "~")]
        return path, frag, node

    def walk(self, node, file: Path, label: str, entry, holder, required: set[str]) -> None:
        if isinstance(node, list):
            for item in node:
                self.walk(item, file, label, entry, holder, required)
            return
        if not isinstance(node, dict):
            return
        ref = node.get("$ref")
        if isinstance(ref, str):
            self.follow(ref, file)
        if isinstance(node.get("name"), str) and "in" in node:
            self.check_name(file, label, "property", node["name"], entry, node, required)
        for key, value in node.items():
            if key == "properties" and isinstance(value, dict):
                for pname, pnode in value.items():
                    self.check_name(file, label, "property", pname, entry, pnode, required)
                    self.walk(pnode, file, label, entry, pnode, required)
            elif key == "enum" and isinstance(value, list):
                for member in value:
                    self.check_name(file, label, "enum_value", member, entry, holder or node, required)
            elif key != "$ref":
                self.walk(value, file, label, entry, holder, required)

    def follow(self, ref: str, file: Path) -> None:
        try:
            path, frag, node = self.resolve(ref, file)
        except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError) as e:
            self.findings.append(f"{file}: cannot resolve $ref '{ref}': {e}")
            return
        key = (str(path), frag)
        if key in self.seen:
            return
        self.seen.add(key)
        parts = [p for p in frag.split("/") if p]
        if len(parts) == 3 and parts[:2] == ["components", "schemas"]:
            self.walk(node, path, f"components.schemas.{parts[2]}", parts[2], None, self.required_of(node))
        elif not frag:
            self.walk(node, path, path.name, None, None, self.required_of(node))
        else:
            self.walk(node, path, f"{path.name}#{frag}", None, None, self.required_of(node))

    def run(self, openapi: Path) -> None:
        doc = self.doc(openapi)
        for pth, item in (doc.get("paths") or {}).items():
            if not isinstance(item, dict):
                continue
            ops = [("", item.get("parameters"))]
            for method, op in item.items():
                if isinstance(op, dict):  # deny by default: an operation without operationId is walked too
                    ops.append((method, op))
            for method, op in ops:
                if method == "":
                    holders = [(f"{pth} parameters", op)]
                else:
                    holders = [(f"{pth} {method} requestBody", op.get("requestBody")),
                               (f"{pth} {method} parameters", op.get("parameters"))]
                for label, part in holders:
                    if part is not None:
                        self.walk(part, openapi, label, None, None, self.required_of(part))


def request_findings(d: Path) -> list[str]:
    rows, errors = read_request_table(d)
    if errors:
        return errors
    openapi = d / "openapi.yaml"
    if not openapi.is_file():
        return [f"{d}: cannot read a contract file: {openapi} is missing"]
    w = RequestWalk(d, rows)
    try:
        w.run(openapi)
    except (OSError, ValueError, yaml.YAMLError, AttributeError, KeyError, TypeError) as e:
        w.findings.append(f"{d}: cannot read a contract file: {e}")
    return w.findings


def main(argv: list[str]) -> int:
    if "--requests" in argv[1:]:
        dirs = [a for a in argv[1:] if a != "--requests"]
        if not dirs:
            print(__doc__)
            return 1
        findings = [f for a in dirs for f in request_findings(Path(a))]
        for f in findings:
            print(f"gst-ci: FAIL {f}")
        if findings:
            return 1
        print(f"gst-ci: forbidden-request-properties OK — {', '.join(dirs)}")
        return 0
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
