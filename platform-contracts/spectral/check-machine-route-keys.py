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

  (e) x-operation of the openapi files (INV-T-18352): for every key row the keyed operation is the one operation
      with that method and path template carrying `x-principal` in <root>/*/openapi.yaml (none, or more than one,
      fails). A directory is swept when a keyed operation of its file has `kind` in its `x-operation`. In a swept
      directory every keyed operation needs an `x-operation` mapping with kind == PERMISSION, permission equal to
      the permission cell, permission-also equal to the permission_also cell (absent when `-`), and for a machine
      row permission-also present if and only if the operation carries `x-principal-also`. In a directory that is
      not swept no keyed operation may carry `permission` or `permission-also`. The last line printed,
      `x-operation-unswept: <directories>`, is information, not a finding;
  (f) operationId: a keyed operation needs a string operationId, and two keyed operations must not share one
      (a pair of a keyed and a non-keyed operation is not a finding).

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
METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
KIND = "PERMISSION"
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


def keyed_operations(root: Path, errors: list) -> dict:
    """(method, path) -> list of (directory, operation) for every operation carrying x-principal."""
    found = {}
    for f in sorted(root.glob("*/openapi.yaml")):
        d = f.parent.name
        try:
            doc = yaml.safe_load(f.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, yaml.YAMLError) as ex:
            errors.append(f"{d}/openapi.yaml: (e) unreadable: {type(ex).__name__}")
            continue
        paths = doc.get("paths") if isinstance(doc, dict) else None
        if not isinstance(paths, dict):
            continue
        for path, item in paths.items():
            if not isinstance(item, dict):
                continue
            for m in METHODS:
                op = item.get(m)
                if isinstance(op, dict) and "x-principal" in op:
                    found.setdefault((m, path), []).append((d, op))
    return found


def check_x_operations(root: Path, key_map: dict, errors: list) -> list:
    """Sections (e) and (f). Returns the sorted directories with keyed operations that are not swept."""
    found = keyed_operations(root, errors)
    keyed = {}  # route -> (dir, op, key row)
    for rk in sorted(key_map):
        w = f"{rk[0]} {rk[1]}"
        ops = found.get(rk, [])
        if not ops:
            errors.append(f"{w}: (e) no operation with this method and path carries x-principal in */openapi.yaml")
        elif len(ops) > 1:
            errors.append(f"{w}: (e) {len(ops)} operations carry x-principal: {', '.join(d + '/openapi.yaml' for d, _ in ops)}")
        else:
            keyed[rk] = (ops[0][0], ops[0][1], key_map[rk])

    def xop(op):
        x = op.get("x-operation")
        return x if isinstance(x, dict) else None

    swept = {d for d, op, _ in keyed.values() if xop(op) is not None and "kind" in xop(op)}
    for rk, (d, op, row) in sorted(keyed.items()):
        w = f"{rk[0]} {rk[1]}"
        x = xop(op)
        _, _, perm, also, by = row
        if d in swept:
            if x is None:
                errors.append(f"{w}: (e) {d}/openapi.yaml is swept but the operation has no x-operation mapping")
                continue
            if "kind" not in x or not isinstance(x["kind"], str) or x["kind"] != KIND:
                errors.append(f"{w}: (e) x-operation kind {x.get('kind')!r} is not {KIND!r}")
            if "permission" not in x or not isinstance(x["permission"], str) or x["permission"] != perm:
                errors.append(f"{w}: (e) x-operation permission {x.get('permission')!r} differs from the key table {perm!r}")
            has_also = "permission-also" in x
            if also != NONE:
                if not has_also or not isinstance(x["permission-also"], str) or x["permission-also"] != also:
                    errors.append(f"{w}: (e) x-operation permission-also {x.get('permission-also')!r} differs from the key table {also!r}")
            elif has_also:
                errors.append(f"{w}: (e) x-operation permission-also {x['permission-also']!r} where the key table has {NONE!r}")
            if by == "machine" and has_also != ("x-principal-also" in op):
                errors.append(f"{w}: (e) permission-also is {'present' if has_also else 'absent'} but x-principal-also is "
                              f"{'present' if 'x-principal-also' in op else 'absent'}")
        elif x is not None:
            for k in ("permission", "permission-also"):
                if k in x:
                    errors.append(f"{w}: (e) {d}/openapi.yaml is not swept (no kind) but its x-operation carries {k}")
    ids = {}
    for rk, (d, op, _) in sorted(keyed.items()):
        w = f"{rk[0]} {rk[1]}"
        oid = op.get("operationId")
        if not isinstance(oid, str):
            errors.append(f"{w}: (f) operationId {oid!r} is not a string")
        else:
            ids.setdefault(oid, []).append(f"{w} ({d}/openapi.yaml)")
    for oid, routes in sorted(ids.items()):
        if len(routes) > 1:
            errors.append(f"{routes[0].split(' (')[0]}: (f) operationId {oid!r} is shared by keyed routes {', '.join(routes)}")
    return sorted({d for d, _, _ in keyed.values()} - swept)


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
    unswept = check_x_operations(root, key_map, errors)
    if errors:
        print("machine-route-keys-check FAILED:")
        for e in errors:
            print(f"  - {e}")
    else:
        print(f"machine-route-keys-check: {len(keys)} routes keyed")
    print("x-operation-unswept: " + (", ".join(unswept) if unswept else NONE))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
