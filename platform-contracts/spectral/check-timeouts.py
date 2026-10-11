#!/usr/bin/env python3
"""spectral/check-timeouts.py — INV-T-3431 (INV-Q-3429 A, INV-REQ-1232 b4, INV-REQ-1204).

Every operation's x-timeout (API-02) must be one of the figures of its edge's
row in INV-NOTE-3 §36.6, which invoice-docs model/architecture/sync-edges.yaml
records as `resilience_policy`. The table is read on every run (never vendored);
no edge and no value is hard-coded here.

For every <service>/openapi.yaml operation with an x-sync-edge `<caller>-><callee>`
the edge's row is found as follows:
  1. the exact `caller → callee` row (the fallback rows `Any → tenancy-svc` and
     `Any → access-svc` are exact rows for `any->tenancy-svc` / `any->access-svc`);
  2. otherwise the caller's `caller → any` row (so console-bff->X, and
     service-runtime's console-bff->*, use `console-bff → any`).
Each `N s` / `N ms` figure in the row's timeout_or_budget is parsed to
milliseconds; a row with several figures (e.g. `150 ms budget (pre-issuance),
2 s (Relay validation)`) accepts any of them. The check fails when x-timeout is
not one of the row's figures.

Listed as unchecked, never failed: operations without an x-sync-edge (public
/v1 and console-bff's own /bff operations), edges with no row, and rows with
no figure (`Per §13.7`, `Per binding`).

Row callers are normalised as check-sync-edges.py normalises allowlist callers:
`Any …` becomes `any`, a gateway (`API gateway`, `Gateway`) becomes
`api-gateway`, anything else is lower-cased with spaces as hyphens; a
`(fallback)` suffix on the callee is dropped.

Usage: check-timeouts.py <sync-edges.yaml> <root>
<root> holds the service directories (the repository root in CI). Exit 0 if
all checked operations pass, 1 otherwise. Needs PyYAML.
"""
import re
import sys
from pathlib import Path

import yaml

METHODS = ("get", "post", "put", "delete", "patch")
FIGURE = re.compile(r"(\d+(?:\.\d+)?)\s*(ms|s)\b")

# TODO(INV-T-3491): these operations' x-timeout and response shape are reserved
# for the owner (INV-Q-3438, INV-Q-3476) and move in INV-T-3491. Until then they
# keep their current x-timeout and are reported as deferred, not failed. Remove
# each one from this set in INV-T-3491 when its value is set. The other
# operations INV-T-3491 owns (requestIssuance and the gateway's other public /v1
# operations, console-web -> console-bff, createTenant) need no entry: they have
# no x-sync-edge or their row gives no figure, so they are listed as unchecked.
DEFERRED_TO_INV_T_3491 = {
    "importMappingSpreadsheet",
    "importRegulatoryPack",
    "provisionTenant",
}


def norm_caller(caller: str) -> str:
    c = caller.strip().lower()
    if c.startswith("any"):
        return "any"
    if c.endswith("gateway"):
        return "api-gateway"
    return c.replace(" ", "-")


def norm_callee(callee: str) -> str:
    c = re.sub(r"\(fallback\)", "", callee).strip().lower()
    return "any" if c.startswith("any") else c.replace(" ", "-")


def figures_ms(text: str) -> list:
    out = []
    for num, unit in FIGURE.findall(str(text)):
        ms = float(num) * (1 if unit == "ms" else 1000)
        out.append(int(ms) if ms == int(ms) else ms)
    return out


def load_rows(path: Path) -> dict:
    rows = {}
    for r in yaml.safe_load(path.read_text())["resilience_policy"]:
        caller, _, callee = r["edge"].partition("→")
        if not callee:
            continue
        rows[(norm_caller(caller), norm_callee(callee))] = r
    return rows


def main(argv) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    rows = load_rows(Path(argv[1]))
    root = Path(argv[2])
    errors, unchecked, deferred, n_ok = [], [], [], 0
    for f in sorted(root.glob("*/openapi.yaml")):
        svc = f.parent.name
        doc = yaml.safe_load(f.read_text())
        for path, item in (doc.get("paths") or {}).items():
            for m in METHODS:
                op = (item or {}).get(m)
                if not isinstance(op, dict):
                    continue
                oid = op.get("operationId")
                timeout = op.get("x-timeout")
                where = f"{svc}: {m.upper()} {path} ({oid}) x-timeout {timeout}"
                edge = op.get("x-sync-edge")
                if oid in DEFERRED_TO_INV_T_3491:
                    deferred.append(f"{where}: reserved for INV-T-3491")
                    continue
                if not edge:
                    unchecked.append(f"{where}: no x-sync-edge (public or console-bff's own operation)")
                    continue
                caller, _, callee = edge.partition("->")
                row = rows.get((caller, callee)) or rows.get((caller, "any"))
                if row is None:
                    unchecked.append(f"{where}: edge {edge} has no resilience_policy row")
                    continue
                allowed = figures_ms(row.get("timeout_or_budget", ""))
                if not allowed:
                    unchecked.append(f"{where}: row '{row['edge']}' gives no figure ({row.get('timeout_or_budget')})")
                    continue
                if timeout not in allowed:
                    errors.append(
                        f"{where}: not a figure of row '{row['edge']}' "
                        f"({row.get('timeout_or_budget')}; allowed {allowed} ms)"
                    )
                else:
                    n_ok += 1
    for title, items in (("unchecked", unchecked), ("deferred", deferred)):
        if items:
            print(f"gst-ci: x-timeout {title} ({len(items)}):")
            for i in items:
                print(f"  - {i}")
    if errors:
        print(f"gst-ci: x-timeout check FAILED ({len(errors)}):")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"gst-ci: x-timeout check passed: {n_ok} operations match their §36.6 row, "
          f"{len(unchecked)} unchecked, {len(deferred)} deferred to INV-T-3491.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
