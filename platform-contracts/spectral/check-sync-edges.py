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
<svc>" (the fallback edges) becomes `any`, "API gateway" becomes `api-gateway`
(lower case, spaces to hyphens), so the operation writes `any->access-svc`.

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
import sys
from pathlib import Path
from urllib.parse import unquote

import yaml

METHODS = ("get", "post", "put", "delete", "patch")
SHARED_RUNTIME = "service-runtime"
SHARED_EDGE = "console-bff->*"
OUTSIDE_PIPELINE = ("console-bff", "api-gateway")


def callee_of(service_dir: str) -> str:
    return service_dir if service_dir == "console-bff" else f"{service_dir}-svc"


def caller_id(caller: str) -> str:
    c = caller.strip()
    if c.lower().startswith("any"):
        return "any"
    return c.lower().replace(" ", "-")


def callee_id(callee: str) -> str:
    return str(callee).strip().lower().replace(" ", "-")


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
                if svc == SHARED_RUNTIME and edge == SHARED_EDGE:
                    continue
                if edge not in allowed:
                    errors.append(f"{where}: x-sync-edge '{edge}' is not an edge of sync-edges.yaml (API-08)")
                elif edge.split("->", 1)[1] != callee_of(svc):
                    errors.append(f"{where}: x-sync-edge '{edge}' names a callee other than the owning service {callee_of(svc)} (API-08)")
    if errors:
        print("gst-ci: API-08 allowlist check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"gst-ci: API-08 allowlist check passed: {n_ops} internal operations, {n_edges} x-sync-edge values all in the allowlist.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
