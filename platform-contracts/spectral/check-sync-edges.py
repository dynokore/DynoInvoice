#!/usr/bin/env python3
"""spectral/check-sync-edges.py — INV-T-2850 follow-up (API-08).

API-08: internal operations serve only the allowlisted synchronous edges; each
carries `x-sync-edge: <caller>-><callee>`. Spectral (inv-api-08-x-sync-edge)
proves the field is present and well-formed; Spectral cannot read the
allowlist, so this script checks, for every <service>/openapi.yaml:

  1. every x-sync-edge value is `<caller>-><callee>` of an edge in
     invoice-docs model/architecture/sync-edges.yaml (the allowlist);
  2. the callee is the service that owns the file (<dir>-svc; console-bff is
     its own name);
  3. every operation under /internal/v1 has an x-sync-edge (no exemptions).

One exception, for files in service-runtime/ only (INV-T-3007; INV-Q-2950,
INV-Q-3425 A): service-runtime/openapi.yaml is the shared runtime contract
every service that owns scheduled jobs mounts (runJobNow, pauseJob,
resumeJob), so it has no single callee and no contract lists a service's jobs
(jobs are known at runtime from JobRegistered, INV-Q-2981). Its operations
carry `x-sync-edge: console-bff->*`, served over console-bff's existing edge
to each owning service. The `*` callee is accepted only there and only for the
caller console-bff; anywhere else it fails as before (not an allowlist edge).
Every other file is checked exactly as before.

Allowlist callers that are not service identifiers are normalised: "Any except
<svc>" (the fallback edges) becomes `any-except:<svc>` (lower case, the one
excluded service after the colon), a plain "Any" stays `any`, "API gateway"
becomes `api-gateway` (lower case, spaces to hyphens), so the operation writes
`any-except:tenancy-svc->access-svc`. A contract value is accepted only when it
equals an allowlist edge by string equality.

x-sync-edge (INV-T-8456, owner ruling 2026-10-22) is one string, or a YAML
sequence of at least two such strings for a shared operation. Each element is
checked as a single string; elements are unique, share one callee, and none is
`any-><callee>` next to a named caller. The exclusion form
`any-except:<svc>-><callee>` is a single string only, with `<svc>` a service
(`-svc`) that differs from the callee. `console-bff->*` is a single string only.

Before the per-operation checks, the allowlist itself is checked (INV-REQ-870,
INV-AC-1922): console-bff and the API gateway sit outside the pipeline, so the
only allowlist edge whose callee is `console-bff` or `api-gateway` (callee
normalised like the caller: stripped, lower case, spaces to hyphens; compared
by equality) is console-web -> console-bff. Any other such edge (a pipeline
service, "Any except <svc>", an installer, the gateway calling console-bff,
anything calling the gateway) is an error.

Usage: check-sync-edges.py <sync-edges.yaml> <root>
<root> holds the service directories (the repository root in CI). Exit 0 if
all pass, 1 otherwise. Needs PyYAML.
"""
import re
import sys
from pathlib import Path
from urllib.parse import unquote

import yaml

METHODS = ("get", "post", "put", "delete", "patch")
SHARED_RUNTIME = "service-runtime"
SHARED_EDGE = "console-bff->*"
OUTSIDE_PIPELINE = ("console-bff", "api-gateway")
ANY_EXCEPT_ROW = re.compile(r"any except ([a-z][a-z0-9-]*-svc)")
EXCLUSION_PREFIX = "any-except:"
EXCLUSION_EDGE = re.compile(r"any-except:([a-z][a-z0-9-]*-svc)->(.+)")


def callee_of(service_dir: str) -> str:
    return service_dir if service_dir == "console-bff" else f"{service_dir}-svc"


def caller_id(caller: str) -> str:
    c = caller.strip().lower()
    m = ANY_EXCEPT_ROW.fullmatch(c)
    if m:
        return EXCLUSION_PREFIX + m.group(1)
    return c.replace(" ", "-")


def callee_id(callee: str) -> str:
    return str(callee).strip().lower().replace(" ", "-")


def element_errors(edge, svc: str, allowed: set, in_sequence: bool) -> list:
    """Problems of one x-sync-edge string (API-08), without its operation prefix."""
    if not isinstance(edge, str):
        return [f"x-sync-edge element {edge!r} is not a string (API-08)"]
    errors = []
    if edge.startswith(EXCLUSION_PREFIX):
        m = EXCLUSION_EDGE.fullmatch(edge)
        if in_sequence:
            errors.append(f"x-sync-edge '{edge}': the exclusion form is valid as a single string only (API-08)")
        elif m is None:
            errors.append(f"x-sync-edge '{edge}' is a malformed exclusion, expected any-except:<svc>-svc-><callee> (API-08)")
        elif m.group(1) == m.group(2):
            errors.append(f"x-sync-edge '{edge}' excludes the callee itself (API-08)")
    if in_sequence and edge.endswith("*"):
        errors.append(f"x-sync-edge '{edge}': a callee * is valid as a single string only (API-08)")
    if svc == SHARED_RUNTIME and edge == SHARED_EDGE:
        return errors
    if edge not in allowed:
        errors.append(f"x-sync-edge '{edge}' is not an edge of sync-edges.yaml (API-08)")
    elif edge.split("->", 1)[1] != callee_of(svc):
        errors.append(f"x-sync-edge '{edge}' names a callee other than the owning service {callee_of(svc)} (API-08)")
    return errors


def sequence_errors(edges: list) -> list:
    """Shape problems of a sequence value (API-08), beyond its elements."""
    errors = []
    if len(edges) < 2:
        errors.append(f"x-sync-edge sequence {edges!r} has fewer than two elements (API-08)")
    strs = [e for e in edges if isinstance(e, str)]
    if len(set(strs)) != len(strs):
        errors.append(f"x-sync-edge sequence {edges!r} repeats an element (API-08)")
    if len({e.split("->", 1)[1] for e in strs if "->" in e}) > 1:
        errors.append(f"x-sync-edge sequence {edges!r} names two different callees (API-08)")
    for e in strs:
        if re.fullmatch(r"any->.*", e) and len(strs) > 1:
            errors.append(f"x-sync-edge sequence {edges!r}: element '{e}' (any caller) cannot sit next to a named caller (API-08)")
    return errors


def outside_pipeline_errors(edges) -> list:
    errors = []
    for e in edges:
        callee = callee_id(e["callee"])
        if callee not in OUTSIDE_PIPELINE:
            continue
        if caller_id(e["caller"]) == "console-web" and callee == "console-bff":
            continue
        errors.append(
            f"sync-edges.yaml: edge '{e['caller']}->{e['callee']}' calls console-bff or the API gateway "
            "from a caller other than console-web (INV-REQ-870, INV-AC-1922)"
        )
    return errors


def main(argv) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    allow_doc = yaml.safe_load(Path(argv[1]).read_text())
    allowed = {f"{caller_id(e['caller'])}->{e['callee']}" for e in allow_doc["edges"]}
    root = Path(argv[2])
    errors, n_ops, n_edges = outside_pipeline_errors(allow_doc["edges"]), 0, 0
    for f in sorted(root.glob("*/openapi.yaml")):
        svc = f.parent.name
        doc = yaml.safe_load(f.read_text())
        for path, item in (doc.get("paths") or {}).items():
            for m in METHODS:
                op = (item or {}).get(m)
                if not isinstance(op, dict):
                    continue
                where = f"{svc}: {m.upper()} {path} ({op.get('operationId')})"
                edge = op.get("x-sync-edge")
                internal = path.startswith("/internal/v1/")
                n_ops += internal
                if edge is None:
                    if internal:
                        errors.append(f"{where}: internal operation has no x-sync-edge (API-08)")
                    continue
                n_edges += 1
                if isinstance(edge, list):
                    for msg in sequence_errors(edge):
                        errors.append(f"{where}: {msg}")
                    for el in edge:
                        for msg in element_errors(el, svc, allowed, True):
                            errors.append(f"{where}: {msg}")
                elif isinstance(edge, str):
                    for msg in element_errors(edge, svc, allowed, False):
                        errors.append(f"{where}: {msg}")
                else:
                    errors.append(f"{where}: x-sync-edge {edge!r} is neither a string nor a sequence of strings (API-08)")
    if errors:
        print("gst-ci: API-08 allowlist check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"gst-ci: API-08 allowlist check passed: {n_ops} internal operations, {n_edges} x-sync-edge values all in the allowlist.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
