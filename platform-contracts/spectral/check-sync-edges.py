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
  3. every operation under /internal/v1 either has an x-sync-edge or is one of
     the explicit architecture-gap exemptions in spectral/ruleset.yaml
     `overrides` (the rule is off for exactly those operations), and an
     exempted operation does not carry an edge (a stale exemption fails).

Usage: check-sync-edges.py <sync-edges.yaml> <ruleset.yaml> <root>
<root> holds the service directories (the repository root in CI). Exit 0 if
all pass, 1 otherwise. Needs PyYAML.
"""
import sys
from pathlib import Path
from urllib.parse import unquote

import yaml

METHODS = ("get", "post", "put", "delete", "patch")


def callee_of(service_dir: str) -> str:
    return service_dir if service_dir == "console-bff" else f"{service_dir}-svc"


def exemptions(ruleset: dict) -> set:
    """(service_dir, path, method) for each override that switches API-08 off."""
    out = set()
    for ov in ruleset.get("overrides", []) or []:
        if (ov.get("rules") or {}).get("inv-api-08-x-sync-edge") != "off":
            continue
        for f in ov.get("files", []):
            file_part, _, ptr = f.partition("#")
            svc = Path(file_part).parts[-2]
            parts = [unquote(p).replace("~1", "/").replace("~0", "~") for p in ptr.split("/")[1:]]
            if len(parts) != 3 or parts[0] != "paths" or parts[2] not in METHODS:
                raise SystemExit(f"gst-ci: unexpected API-08 exemption pointer: {f}")
            out.add((svc, parts[1], parts[2]))
    return out


def main(argv) -> int:
    if len(argv) != 4:
        print(__doc__)
        return 2
    allow_doc = yaml.safe_load(Path(argv[1]).read_text())
    allowed = {f"{e['caller']}->{e['callee']}" for e in allow_doc["edges"]}
    exempt = exemptions(yaml.safe_load(Path(argv[2]).read_text()))
    root = Path(argv[3])
    errors, seen_exempt, n_ops, n_edges = [], set(), 0, 0
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
                if (svc, path, m) in exempt:
                    seen_exempt.add((svc, path, m))
                    if edge is not None:
                        errors.append(f"{where}: stale API-08 exemption in spectral/ruleset.yaml, the operation has x-sync-edge '{edge}'")
                    continue
                if edge is None:
                    if internal:
                        errors.append(f"{where}: internal operation has no x-sync-edge (API-08)")
                    continue
                n_edges += 1
                if edge not in allowed:
                    errors.append(f"{where}: x-sync-edge '{edge}' is not an edge of sync-edges.yaml (API-08)")
                elif edge.split("->", 1)[1] != callee_of(svc):
                    errors.append(f"{where}: x-sync-edge '{edge}' names a callee other than the owning service {callee_of(svc)} (API-08)")
    for svc, path, m in sorted(exempt - seen_exempt):
        errors.append(f"{svc}: {m.upper()} {path}: API-08 exemption in spectral/ruleset.yaml names an operation that does not exist")
    for svc, path, m in sorted(seen_exempt):
        print(f"gst-ci: API-08 architecture gap (exempt, no allowlist edge): {svc} {m.upper()} {path}")
    if errors:
        print("gst-ci: API-08 allowlist check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"gst-ci: API-08 allowlist check passed: {n_ops} internal operations, {n_edges} x-sync-edge values all in the allowlist, {len(seen_exempt)} exempt gaps.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
