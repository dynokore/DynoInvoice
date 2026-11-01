#!/usr/bin/env python3
"""spectral/check-flow-resumption.py — INV-T-14114 (INV-Q-14102 A, INV-REQ-1008, INV-AC-14113)

Fails closed when the table catalogue/flow-resumption.tsv does not map each of the 11 resumption
points of INV-REQ-1008 behavior 1 to a GET operation of the owning service, or to a pinned UNREAD.
Columns, tab separated: flow, step, service, operationId, gap. `service` is the name of a contract
directory with an `openapi.yaml` at the root. A row is UNREAD exactly when its (flow, step) is in
UNREAD_PINNED; the gap cell is INV-Q-14102 for UNREAD and `-` otherwise. A later read addition maps
the row and removes it from the pin. Cells are compared by equality, never trimmed.

Every finding is one line prefixed `flow-resumption.tsv: `; exit 1 on any finding.

Usage: check-flow-resumption.py [ROOT]      (default ROOT: the current directory)
Standard library plus PyYAML, as the other spectral checks.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

POINTS = (
    ("pack-authoring-to-activation", "draft", "rules"),
    ("pack-authoring-to-activation", "dry-run-result", "rules"),
    ("pack-authoring-to-activation", "approval", "rules"),
    ("pack-authoring-to-activation", "acknowledgements", "rules"),
    ("onboarding-to-go-live", "coverage-map", "ingestion"),
    ("onboarding-to-go-live", "gap-report", "masterdata"),
    ("onboarding-to-go-live", "certification-results", "tenancy"),
    ("period-readiness-to-filing", "verdict", "submission"),
    ("period-readiness-to-filing", "drain-progress", "submission"),
    ("period-readiness-to-filing", "residual-list", "submission"),
    ("compensation", "affected-population-definition", "rules"),
)
UNREAD_PINNED = (
    ("pack-authoring-to-activation", "acknowledgements"),
    ("onboarding-to-go-live", "coverage-map"),
    ("onboarding-to-go-live", "certification-results"),
    ("period-readiness-to-filing", "drain-progress"),
    ("period-readiness-to-filing", "residual-list"),
    ("compensation", "affected-population-definition"),
)
HEADER = ["flow", "step", "service", "operationId", "gap"]
TABLE = "flow-resumption.tsv"
UNREAD = "UNREAD"
QUESTION = "INV-Q-14102"
METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")


def operations(doc) -> dict[str, tuple[str, dict]]:
    """operationId -> (lower-case method, operation object) for every operation under `paths`."""
    ops: dict[str, tuple[str, dict]] = {}
    paths = doc.get("paths") if isinstance(doc, dict) else None
    for item in (paths.values() if isinstance(paths, dict) else ()):
        for method, op in (item.items() if isinstance(item, dict) else ()):
            if method in METHODS and isinstance(op, dict) and isinstance(op.get("operationId"), str):
                ops[op["operationId"]] = (method, op)
    return ops


def has_body(op: dict) -> bool:
    responses = op.get("responses")
    if not isinstance(responses, dict):
        return False
    return any(str(code).startswith("2") and isinstance(r, dict) and "content" in r for code, r in responses.items())


def load_services(root: Path, findings: list[str]) -> dict[str, dict[str, tuple[str, dict]]]:
    services: dict[str, dict[str, tuple[str, dict]]] = {}
    for path in sorted(root.glob("*/openapi.yaml")):
        name = path.parent.name
        try:
            doc = yaml.safe_load(path.read_text())
        except (yaml.YAMLError, OSError, UnicodeError) as e:
            findings.append(f"{TABLE}: {name}/openapi.yaml cannot be parsed: {str(e).splitlines()[0] if str(e) else type(e).__name__}")
            continue
        services[name] = operations(doc)
    return services


def check(root: Path) -> tuple[list[str], int]:
    findings: list[str] = []
    try:
        with open(root / "catalogue" / TABLE, newline="") as fh:
            text = fh.read()
    except (OSError, UnicodeError) as e:
        return [f"{TABLE}: cannot be read: {type(e).__name__}"], 0
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if not lines or not any(l.strip() for l in lines):
        return [f"{TABLE}: is empty"], 0
    services = load_services(root, findings)
    owner = {(f, s): o for f, s, o in POINTS}
    if lines[0].split("\t") != HEADER:
        findings.append(f"{TABLE}: the header differs from {', '.join(HEADER)}")

    seen: dict[tuple[str, str], int] = {}
    for no, raw in enumerate(lines[1:], 2):
        cells = raw.split("\t")
        if len(cells) != 5:
            findings.append(f"{TABLE}: line {no} has {len(cells)} columns, not five")
            continue
        if any(c == "" for c in cells):
            findings.append(f"{TABLE}: line {no} has an empty cell")
            continue
        flow, step, svc, op, gap = cells
        point = (flow, step)
        if point not in owner:
            findings.append(f"{TABLE}: line {no}: ({flow}, {step}) is not a resumption point of INV-REQ-1008")
            continue
        if point in seen:
            findings.append(f"{TABLE}: line {no} repeats line {seen[point]}")
        else:
            seen[point] = no
        if svc != owner[point]:
            findings.append(f"{TABLE}: line {no}: service '{svc}' is not '{owner[point]}', the owner of {flow} {step}")
        pinned = point in UNREAD_PINNED
        if op == UNREAD:
            if not pinned:
                findings.append(f"{TABLE}: line {no}: {flow} {step} is not pinned UNREAD")
        else:
            if pinned:
                findings.append(f"{TABLE}: line {no}: {flow} {step} is pinned UNREAD but maps to operationId '{op}'")
            ops = services.get(svc) if re.fullmatch(r"[A-Za-z0-9_-]+", svc) else None
            if ops is None:
                findings.append(f"{TABLE}: line {no}: service '{svc}' has no openapi.yaml")
            elif op not in ops:
                findings.append(f"{TABLE}: line {no}: operationId '{op}' is not an operationId of {svc}/openapi.yaml")
            elif ops[op][0] != "get":
                findings.append(f"{TABLE}: line {no}: operationId '{op}' of {svc}/openapi.yaml is not a GET operation")
            elif not has_body(ops[op][1]):
                findings.append(f"{TABLE}: line {no}: operationId '{op}' of {svc}/openapi.yaml has no 2xx response with a body")
        want = QUESTION if pinned else "-"
        if gap != want:
            findings.append(f"{TABLE}: line {no}: gap '{gap}' must be '{want}'")
    for flow, step, _ in POINTS:
        if (flow, step) not in seen:
            findings.append(f"{TABLE}: resumption point ({flow}, {step}) has no line")
    return findings, len(POINTS)


def main(argv: list[str]) -> int:
    args = argv[1:]
    if len(args) > 1:
        print("flow-resumption: usage: check-flow-resumption.py [ROOT]")
        return 1
    findings, count = check(Path(args[0]) if args else Path("."))
    if findings:
        print("gst-ci: flow-resumption FAIL")
        for f in findings:
            print(f)
        return 1
    print(f"flow resumption: {count} points, {len(UNREAD_PINNED)} unread")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
