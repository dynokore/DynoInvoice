#!/usr/bin/env python3
"""spectral/check-machine-route-keys.py — INV-T-13571 (INV-AC-13563; INV-Q-13559 B; INV-Q-10649 C; INV-Q-15877 A).

Fail-closed gate: every row of <root>/route-scopes/machine-routes.tsv names exactly one deciding permission key
in <root>/route-scopes/machine-route-keys.tsv (OWASP ASVS V4.1, NIST SP 800-53 AC-3). Keys, types and flags are
compared by equality only (no strip, no case folding, no prefix, no glob). It fails, naming the route, when:

  (a) a route row has no key row, a key row has no route row, or a (method, path) pair is duplicated;
  (b) decided_by is not `machine` or `person`;
  (c) a `machine` row: permission does not start with `machine.`, or is not `machine.` + a permission of the
      row's `scope` type in access/schemas/machine-principal-permissions.tsv; permission_also is not `-` when
      also_scope is `-`; is `-` when also_scope is not `-`; or is not `machine.` + a permission of the also_scope type;
  (d) a `person` row: permission starts with `machine.` or is not a key of
      catalogue/role-permissions/role-permissions.yaml; permission_also is not `-` and is not a `machine.` key held
      by the row's `scope` type; or the route is not one of the PERSON_ROUTES, or a PERSON_ROUTES route is not `person`;
      or permission_also differs from the one pinned for that route in PERSON_ALSO (portfolio: none).

PERSON_ROUTES is the reviewed allowlist of person-decided routes: the two end-user routes of console-bff/openapi.yaml
(INV-Q-13559). PERSON_ALSO pins the only permission-also a person route may carry: get /v1/exceptions keeps
machine.status.read for the machine type `reporting` (owner answer INV-Q-15877 A).

Usage: check-machine-route-keys.py <root>   (needs python3 and PyYAML)
"""
import sys
from pathlib import Path

import yaml

ROUTES = "route-scopes/machine-routes.tsv"
KEYS = "route-scopes/machine-route-keys.tsv"
PERMS = "access/schemas/machine-principal-permissions.tsv"
CATALOGUE = "catalogue/role-permissions/role-permissions.yaml"
ROUTE_HEADER = ["method", "path_template", "scope", "also_scope"]
KEY_HEADER = ["method", "path_template", "permission", "permission_also", "decided_by"]
PERM_HEADER = ["principalType", "permission", "source"]
PREFIX = "machine."
NONE = "-"
# Allowlist: person-decided routes (INV-Q-13559) with the permission-also each may carry (INV-Q-15877 A).
PERSON_ROUTES = {("get", "/v1/exceptions"): "machine.status.read", ("get", "/v1/portfolio"): NONE}


def read_tsv(path: Path, header: list, errors: list, skip_comments=False) -> list:
    try:
        lines = path.read_text(encoding="utf-8").split("\n")
    except (OSError, UnicodeDecodeError) as ex:
        errors.append(f"{path.name}: unreadable: {ex}")
        return []
    if lines and lines[-1] == "":
        lines.pop()
    if skip_comments:
        lines = [l for l in lines if not l.startswith("#")]
    if not lines or lines[0].split("\t") != header:
        errors.append(f"{path.name}: the header line must be {chr(9).join(header)}")
        return []
    rows = []
    for n, line in enumerate(lines[1:], 2):
        f = line.split("\t")
        if len(f) != len(header):
            errors.append(f"{path.name}: line {n} has {len(f)} fields, expected {len(header)}")
        else:
            rows.append(tuple(f))
    return rows


def held_by(perms: dict, type_name: str, key: str) -> bool:
    """key is `machine.` + a permission of principal type type_name (type compared as scope slug -> UPPER_SNAKE)."""
    t = type_name.upper().replace("-", "_")
    return key.startswith(PREFIX) and key[len(PREFIX):] in perms.get(t, set()) and type_name == t.lower().replace("_", "-")


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = Path(argv[1])
    errors = []
    routes = read_tsv(root / ROUTES, ROUTE_HEADER, errors)
    keys = read_tsv(root / KEYS, KEY_HEADER, errors)
    perm_rows = read_tsv(root / PERMS, PERM_HEADER, errors, skip_comments=True)
    perms = {}
    for t, p, _ in perm_rows:
        perms.setdefault(t, set()).add(p)
    catalogue = set()
    try:
        doc = yaml.safe_load((root / CATALOGUE).read_text(encoding="utf-8"))
        for e in doc["permissions"]:
            catalogue.add(e["key"])
    except (OSError, UnicodeDecodeError, yaml.YAMLError, KeyError, TypeError) as ex:
        errors.append(f"{CATALOGUE}: unreadable or without a permissions list of keys: {type(ex).__name__}")

    route_map, key_map = {}, {}
    for r in routes:
        if (r[0], r[1]) in route_map:
            errors.append(f"{r[0]} {r[1]}: (a) duplicate row in machine-routes.tsv")
        route_map[(r[0], r[1])] = r
    for k in keys:
        if (k[0], k[1]) in key_map:
            errors.append(f"{k[0]} {k[1]}: (a) duplicate row in machine-route-keys.tsv")
        key_map[(k[0], k[1])] = k
    for rk in sorted(route_map.keys() - key_map.keys()):
        errors.append(f"{rk[0]} {rk[1]}: (a) route has no row in machine-route-keys.tsv")
    for rk in sorted(key_map.keys() - route_map.keys()):
        errors.append(f"{rk[0]} {rk[1]}: (a) key row has no route in machine-routes.tsv")

    for rk in sorted(route_map.keys() & key_map.keys()):
        _, _, scope, also_scope = route_map[rk]
        _, _, perm, also, by = key_map[rk]
        w = f"{rk[0]} {rk[1]}"
        if by not in ("machine", "person"):
            errors.append(f"{w}: (b) decided_by {by!r} is not machine or person")
            continue
        if by == "machine":
            if rk in PERSON_ROUTES:
                errors.append(f"{w}: (d) a person route must have decided_by person")
            if not perm.startswith(PREFIX):
                errors.append(f"{w}: (c) machine row permission {perm!r} does not start with {PREFIX!r}")
            elif not held_by(perms, scope, perm):
                errors.append(f"{w}: (c) permission {perm!r} is not {PREFIX} + a permission of the scope type {scope!r}")
            if also_scope == NONE:
                if also != NONE:
                    errors.append(f"{w}: (c) permission_also {also!r} on a route whose also_scope is {NONE!r}")
            elif also == NONE:
                errors.append(f"{w}: (c) permission_also is {NONE!r} but also_scope is {also_scope!r}")
            elif not held_by(perms, also_scope, also):
                errors.append(f"{w}: (c) permission_also {also!r} is not held by the also_scope type {also_scope!r}")
        else:
            if rk not in PERSON_ROUTES:
                errors.append(f"{w}: (d) person row, but the route is not in the person-route allowlist")
            if perm.startswith(PREFIX):
                errors.append(f"{w}: (d) person row permission {perm!r} starts with {PREFIX!r}")
            elif perm not in catalogue:
                errors.append(f"{w}: (d) person row permission {perm!r} is not a key of role-permissions.yaml")
            if also != NONE and not held_by(perms, scope, also):
                errors.append(f"{w}: (d) permission_also {also!r} is not a machine key held by the scope type {scope!r}")
            if rk in PERSON_ROUTES and also != PERSON_ROUTES[rk]:
                errors.append(f"{w}: (d) permission_also {also!r} differs from the pinned {PERSON_ROUTES[rk]!r}")
    if errors:
        print("machine-route-keys-check FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"machine-route-keys-check: {len(keys)} routes keyed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
