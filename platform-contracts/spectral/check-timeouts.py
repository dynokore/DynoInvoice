#!/usr/bin/env python3
"""spectral/check-timeouts.py — INV-T-3431 (INV-Q-3429 A, INV-REQ-1232 b4, INV-REQ-1204);
public operations added by INV-T-3491 (INV-REQ-1204 b7, INV-Q-3476, INV-Q-3510).

Every operation's x-timeout (API-02) must be one of the figures of its edge's
row in INV-NOTE-3 §36.6, which invoice-docs model/architecture/sync-edges.yaml
records as `resilience_policy`. The table is read on every run (never vendored);
no edge and no value is hard-coded here.

For every <service>/openapi.yaml operation the edge `<caller>-><callee>` is its
x-sync-edge. A public operation carries no x-sync-edge (API-08 applies to
/internal/v1 only), so its edge is taken from where it is served: the callee is
the file's service (`<dir>-svc`, console-bff its own name, as check-sync-edges.py
names it) and the caller is console-web for console-bff/openapi.yaml (its only
caller, sync-edges.yaml `console-web -> console-bff`) and the API gateway for
every other file's public /v1 operations. The edge's row is found as follows:
  1. the operation's own row, `caller → callee <operationId>` (e.g. `API gateway
     → ingestion-svc requestIssuance`, the issuance class);
  2. the exact `caller → callee` row (the fallback rows `Any → tenancy-svc` and
     `Any → access-svc` are exact rows for `any->tenancy-svc` / `any->access-svc`);
  3. otherwise the caller's `caller → any` row (so console-bff->X, and
     service-runtime's console-bff->*, use `console-bff → any`; a gateway public
     /v1 operation with no row of its own uses `API gateway → any other
     service's public /v1 operation`).
Each `N s` / `N ms` figure in the row's timeout_or_budget is parsed to
milliseconds; a row with several figures (e.g. `150 ms budget (pre-issuance),
2 s (Relay validation)`) accepts any of them. The check fails when x-timeout is
not one of the row's figures.

x-sync-edge may be a sequence of such strings (INV-T-8456): the x-timeout must
satisfy the row of every element, and an error names the element whose row it
does not satisfy. The exclusion edge `any-except:<svc>-><callee>` finds the row
of the caller `any` (the rows `Any → tenancy-svc` and `Any → access-svc`).

Listed as unchecked, never failed: edges with no row, and rows with no figure
(`Per §13.7`, `Per binding`).

Row callers are normalised as check-sync-edges.py normalises allowlist callers:
`Any …` becomes `any`, a gateway (`API gateway`, `Gateway`) becomes
`api-gateway`, anything else is lower-cased with spaces as hyphens; a
`(fallback)` suffix on the callee is dropped, and a trailing operationId
(a lowerCamelCase word, e.g. `requestIssuance`) makes the row that operation's own.

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

OPERATION_ID = re.compile(r"^[a-z]+[A-Z][A-Za-z0-9]*$")
# Where a public operation (no x-sync-edge) is served decides its caller: the
# serving topology of INV-NOTE-3 §2.5 / §34.1, not an edge or a value.
BROWSER_FACING = "console-bff"


def norm_caller(caller: str) -> str:
    c = caller.strip().lower()
    if c.startswith("any"):
        return "any"
    if c.endswith("gateway"):
        return "api-gateway"
    return c.replace(" ", "-")


EXCLUSION_CALLER = re.compile(r"any-except:[a-z][a-z0-9-]*-svc")


def edge_caller(caller: str) -> str:
    """The caller key of an edge string; the exclusion form finds the rows of `any`."""
    return "any" if EXCLUSION_CALLER.fullmatch(caller) else caller


def split_callee(callee: str) -> tuple:
    """(normalised callee, operationId or None) of a row's callee text."""
    c = re.sub(r"\(fallback\)", "", callee).strip()
    op = None
    words = c.split()
    if len(words) > 1 and OPERATION_ID.match(words[-1]):
        op = words[-1]
        c = " ".join(words[:-1])
    c = c.lower()
    return ("any" if c.startswith("any") else c.replace(" ", "-")), op


def callee_of(service_dir: str) -> str:
    return service_dir if service_dir == BROWSER_FACING else f"{service_dir}-svc"


def public_edge(service_dir: str) -> str:
    caller = "console-web" if service_dir == BROWSER_FACING else "api-gateway"
    return f"{caller}->{callee_of(service_dir)}"


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
        callee, op = split_callee(callee)
        rows[(norm_caller(caller), callee, op)] = r
    return rows


def main(argv) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    rows = load_rows(Path(argv[1]))
    root = Path(argv[2])
    errors, unchecked, n_ok = [], [], 0
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
                edge = op.get("x-sync-edge") or public_edge(svc)
                if isinstance(edge, list):
                    elements, prefix = edge, "element {!r}: "
                else:
                    elements, prefix = [edge], ""
                op_ok = True
                for el in elements:
                    pre = prefix.format(el) if prefix else ""
                    if not isinstance(el, str):
                        unchecked.append(f"{where}: {pre}edge {el!r} is not a string")
                        op_ok = False
                        continue
                    caller, _, callee = el.partition("->")
                    caller = edge_caller(caller)
                    row = (rows.get((caller, callee, oid)) or rows.get((caller, callee, None))
                           or rows.get((caller, "any", None)))
                    if row is None:
                        unchecked.append(f"{where}: {pre}edge {el} has no resilience_policy row")
                        op_ok = False
                        continue
                    allowed = figures_ms(row.get("timeout_or_budget", ""))
                    if not allowed:
                        unchecked.append(f"{where}: {pre}row '{row['edge']}' gives no figure ({row.get('timeout_or_budget')})")
                        op_ok = False
                        continue
                    if timeout not in allowed:
                        errors.append(
                            f"{where}: {pre}not a figure of row '{row['edge']}' "
                            f"({row.get('timeout_or_budget')}; allowed {allowed} ms)"
                        )
                        op_ok = False
                if op_ok:
                    n_ok += 1
    if unchecked:
        print(f"gst-ci: x-timeout unchecked ({len(unchecked)}):")
        for i in unchecked:
            print(f"  - {i}")
    if errors:
        print(f"gst-ci: x-timeout check FAILED ({len(errors)}):")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"gst-ci: x-timeout check passed: {n_ok} operations match their §36.6 row, "
          f"{len(unchecked)} unchecked.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
