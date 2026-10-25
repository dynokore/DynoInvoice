#!/usr/bin/env python3
"""check-drift.py — INV-T-2852 (T8).

Re-extracts the Keel INV variation-point requirements (the same read-only query
recorded in variation-points.yaml's own header) and reports whether the committed,
vendored copy has drifted from live Keel.

Not wired into GitLab CI (named as a follow-up in this directory's README): unlike
invoice-docs, which platform-contracts' CI can already clone with
gitlab-ci-token:$CI_JOB_TOKEN (catalogue-drift-check, T7; lifecycle-states-drift-check,
T15), there is no established mechanism yet for a CI job to reach Keel's read-only
database. This script instead runs wherever CODER-BRIEF's own Keel setup already
runs — an inv-coder or inv-reviewer agent that has `source ~/projects/invoice/tooling/k.sh`
in its environment — as the re-vendor procedure's first step (see README.md).

Usage:
    source ~/projects/invoice/tooling/k.sh
    python3 catalogue/variation-points/check-drift.py [--write] [path/to/variation-points.yaml]

Setting permission (INV-T-10133, INV-Q-10127 A): each row's `set_permission` is
io_contract.set_permission (the permission key that sets the variation point; it replaced `set_by`).
A missing or empty value exits 2 naming the row and writes nothing, except VP-27 (retired
requirement), whose key RETIRED_SET_PERMISSION supplies.

--write (INV-T-3000) re-vendors: it rewrites everything below the file's header from
live Keel instead of diffing. Each row's `catalogue` block (scope levels, option keys,
per-option strictness, typed values, INV-Q-2934, INV-Q-2936) is io_contract.catalogue,
copied unedited; a row Keel has not filled yet renders `catalogue: null`.

Installation-parameter rows (INV-T-3377; INV-Q-3202, P57): a variation point whose
installation maximum Keel made its own row (VP-57 -> VP-79, VP-67 -> VP-80) states the
mapping in its own published catalogue as `installation_parameter: {row: VP-nn,
requirement: INV-REQ-nnnn}`. Keel writes `entity.source_ids` only when an entity is
created (P57), so INV-REQ-3210/3211 carry no `VP-nn` source id and the source-id query
alone misses them. The re-vendor therefore also takes every published requirement that
some row's `installation_parameter.requirement` names, with that `installation_parameter.row`
as its id (`derive_parameter_rows`). It fails closed (exit 2, nothing written) when:
  - two referencers name different rows for the same requirement;
  - the requirement already has source ids and they are not exactly the derived id
    (once Keel gains them, the derived and stored ids must agree);
  - the derived id is already the id of another requirement's row;
  - the named requirement is not a published INV requirement.
The generator's own `checkVariationPointFields` still fails any bound or parameter row
that names a variation point missing from the vendored file (dangling references).
Tests: `python3 -m unittest discover -s catalogue/variation-points -p 'test_*.py'`.

Exit code 0: no drift (or re-vendored). Exit code 1: drift found (a diff is printed)
or the vendored file's header could not be read. This script only reads Keel; it
writes the vendored file only under --write.
"""
from __future__ import annotations

import difflib
import re
import sys
from pathlib import Path

QUERY = """
select json_agg(json_build_object(
    'k', e.key, 'src', e.source_ids, 'rev', r.id,
    'io', r.attrs->'io_contract', 't', r.title, 'beh', r.attrs->>'behavior'))
from entity e join entity_revision r on r.entity_id = e.id
  and r.published_in is not null and r.superseded_in is null
where e.project_id = (select id from project where code = 'INV')
  and e.type = 'requirement' and e.source_ids::text like '%VP-%'
"""

# Mirrors variation-points.yaml's own header: the two VPs INV-REQ-1248 behaviour #2
# itself names as its own examples of an option that depends on a form. Kept in sync
# by hand (same as the vendored file itself) — this script does not infer them.
PARAMETER_QUERY = """
select json_agg(json_build_object(
    'k', e.key, 'src', e.source_ids, 'rev', r.id,
    'io', r.attrs->'io_contract', 't', r.title, 'beh', r.attrs->>'behavior'))
from entity e join entity_revision r on r.entity_id = e.id
  and r.published_in is not null and r.superseded_in is null
where e.project_id = (select id from project where code = 'INV')
  and e.type = 'requirement' and e.key = any(array[{keys}])
"""

VP_ID = re.compile(r"^VP-\d+$")

# INV-T-10133: the requirement of VP-27 (INV-REQ-1288) is retired and cannot be revised, so its
# io_contract has no set_permission; this is the one row whose key the tool supplies. Compared with
# the row id by equality; a published value that differs from the constant is an error.
RETIRED_SET_PERMISSION = {"VP-27": "vp-27.set"}


class SetPermissionError(Exception):
    """A row has no usable io_contract.set_permission (INV-T-10133); nothing is written."""


def resolve_set_permissions(rows: list[dict]) -> list[dict]:
    """Return [rows] with io.set_permission set on every row, or raise SetPermissionError naming the row."""
    out = []
    for d in rows:
        vp_id = d["src"][0]
        io = dict(d["io"] or {})
        published = io.get("set_permission")
        fixed = RETIRED_SET_PERMISSION.get(vp_id)
        if fixed is not None:
            if published is not None and published != fixed:
                raise SetPermissionError(f"{vp_id}: published set_permission {published!r} differs from {fixed!r}")
            io["set_permission"] = fixed
        elif not isinstance(published, str) or not published:
            raise SetPermissionError(f"{vp_id}: io_contract has no set_permission")
        out.append({**d, "io": io})
    return out


class DerivationError(Exception):
    """A fail-closed condition of derive_parameter_rows (INV-Q-3202, P57)."""


def parameter_references(rows: list[dict]) -> dict[str, set[tuple[str, str]]]:
    """requirement key -> {(referencing VP id, named row id)} from each row's installation_parameter."""
    refs: dict[str, set[tuple[str, str]]] = {}
    for d in rows:
        param = ((d.get("io") or {}).get("catalogue") or {}).get("installation_parameter") or {}
        if "row" not in param:
            continue
        req, row = param.get("requirement"), param.get("row")
        if not isinstance(req, str) or not isinstance(row, str) or not VP_ID.match(row):
            raise DerivationError(f"{d['src'][0]}: installation_parameter row {row!r} / requirement {req!r} is malformed")
        refs.setdefault(req, set()).add((d["src"][0], row))
    return refs


def derive_parameter_rows(rows: list[dict], fetch) -> list[dict]:
    """The installation-parameter rows to vendor beside [rows] (the source-id query's result).

    [fetch] takes requirement keys and returns their published Keel rows (same shape as
    QUERY's). A referenced requirement already in [rows] (it has its VP source id) must carry
    the derived id; it is not added twice. Raises DerivationError on any disagreement.
    """
    refs = parameter_references(rows)
    by_req = {d["k"]: d for d in rows}
    id_owner = {d["src"][0]: d["k"] for d in rows}
    derived: dict[str, str] = {}
    for req, pairs in sorted(refs.items()):
        named = {row for _, row in pairs}
        if len(named) != 1:
            who = ", ".join(f"{vp} -> {row}" for vp, row in sorted(pairs))
            raise DerivationError(f"{req}: referencers disagree on its row id ({who})")
        derived[req] = named.pop()
    missing = [r for r in derived if r not in by_req]
    fetched = {d["k"]: d for d in (fetch(missing) if missing else [])}
    out = []
    for req, row in derived.items():
        if req in by_req:
            stored = by_req[req]["src"]
            if stored != [row]:
                raise DerivationError(f"{req}: stored source ids {stored} disagree with the derived id {row}")
            continue
        d = fetched.get(req)
        if d is None:
            raise DerivationError(f"{req} (named as {row}) is not a published INV requirement")
        if d["src"]:
            raise DerivationError(f"{req}: source ids {d['src']} disagree with the derived id {row}")
        if row in id_owner and id_owner[row] != req:
            raise DerivationError(f"derived id {row} for {req} collides with {id_owner[row]}")
        id_owner[row] = req
        out.append({**d, "src": [row]})
    return out


def fetch_published(keys: list[str]) -> list[dict]:
    import kdb  # deferred, as in main()

    quoted = ", ".join("'" + k.replace("'", "''") + "'" for k in keys)
    return kdb.q(PARAMETER_QUERY.format(keys=quoted)) or []


FORM_DEPENDENCE = {
    "VP-45": (
        "SaaS only: the VP's own title and behaviour frame this as \"every SaaS tenant "
        "elects its data region\" (io_contract.owner control-plane-svc, scope tenant) "
        "— a private installation's data region is fixed by where the customer "
        "deploys it, not elected through this variation point (INV-REQ-1306, cited by "
        "INV-REQ-1248 behaviour #2 as its own example)."
    ),
    "VP-30": (
        "The \"signed request link\" and \"both\" options need a public endpoint that a "
        "private installation may not expose (bounds: \"Link pages need a public "
        "endpoint in private form (INV-REQ-903)\"); a private installation without that "
        "endpoint exposed is limited to \"CSV exchange only\" or \"off\" (INV-REQ-1291, "
        "cited by INV-REQ-1248 behaviour #2 as its own example)."
    ),
}


def vp_num(src: list[str]) -> int:
    for s in src:
        m = re.match(r"VP-(\d+)", s)
        if m:
            return int(m.group(1))
    return 999


def yaml_str(s):
    if s is None:
        return "null"
    escaped = str(s).replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def yaml_list(items, indent):
    if not items:
        return " []"
    pad = " " * indent
    out = "\n"
    for it in items:
        out += f"{pad}- {yaml_str(it)}\n"
    return out.rstrip("\n")


# Preferred key order inside a row's `catalogue` block (INV-T-3000); any key Keel adds
# later is rendered after these, in Keel's own order, so nothing is silently dropped.
CATALOGUE_ORDER = [
    "scope_levels", "must_be_elected", "multi_select", "exclusive_keys", "options",
    "cross_scope", "cross_option", "cross_row", "note", "basis",
]
OPTION_ORDER = ["key", "strictness", "typed_value"]
TYPED_ORDER = [
    "type", "unit", "min", "max", "default", "direction", "ref", "filter", "ordered", "requires", "fields",
]


def ordered_keys(d: dict, order: list[str]) -> list[str]:
    return [k for k in order if k in d] + [k for k in d if k not in order]


def yaml_scalar(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    return yaml_str(v)


def yaml_node(v, indent: int, order: list[str], child_order) -> list[str]:
    """Block-style YAML for a nested Keel value (`catalogue`, INV-T-3000)."""
    pad = " " * indent
    out = []
    if isinstance(v, dict):
        for k in ordered_keys(v, order):
            val = v[k]
            sub_order, sub_child = child_order(k)
            if isinstance(val, dict) and val:
                out.append(f"{pad}{k}:")
                out.extend(yaml_node(val, indent + 2, sub_order, sub_child))
            elif isinstance(val, list) and val:
                out.append(f"{pad}{k}:")
                out.extend(yaml_node(val, indent + 2, sub_order, sub_child))
            elif isinstance(val, (dict, list)):
                out.append(f"{pad}{k}: {'{}' if isinstance(val, dict) else '[]'}")
            else:
                out.append(f"{pad}{k}: {yaml_scalar(val)}")
    else:  # list
        for item in v:
            if isinstance(item, dict):
                inner = yaml_node(item, indent + 2, order, child_order)
                out.append(f"{pad}- {inner[0].lstrip()}")
                out.extend(inner[1:])
            else:
                out.append(f"{pad}- {yaml_scalar(item)}")
    return out


def typed_child(key: str):
    if key == "fields":
        # a record's fields: names map to typed values
        return [], lambda _k: (TYPED_ORDER, typed_child)
    return TYPED_ORDER, typed_child


def catalogue_child(key: str):
    if key == "options":
        return OPTION_ORDER, lambda k: (TYPED_ORDER, typed_child) if k == "typed_value" else ([], catalogue_child)
    return [], catalogue_child


def render_catalogue(cat) -> list[str]:
    if not cat:
        return ["  catalogue: null"]
    return ["  catalogue:"] + yaml_node(cat, 4, CATALOGUE_ORDER, catalogue_child)


def render_body(rows: list[dict]) -> str:
    rows = sorted(rows, key=lambda x: vp_num(x["src"]))
    lines = []
    for d in rows:
        io = d["io"] or {}
        vp_id = d["src"][0]
        class_raw = io.get("class") or ""
        parts = [p.strip() for p in class_raw.split(",")]
        base_class = parts[0] if parts else None
        modifiers = [p.lower().replace(" ", "_").replace("-", "_") for p in parts[1:]]
        default_raw = io.get("default")
        must_be_elected = bool(default_raw and default_raw.strip().lower().startswith("none: must be elected"))
        default_val = None if must_be_elected else default_raw
        status_raw = (io.get("status") or "").strip()
        m = re.match(r"^(available|planned|retired)\b\s*(?:\((.*)\))?\s*$", status_raw)
        status, status_note = (m.group(1), m.group(2)) if m else (status_raw, None)

        lines.append(f"- id: {vp_id}")
        lines.append(f"  requirement: {d['k']}")
        lines.append(f"  revision: {d['rev']}")
        lines.append(f"  title: {yaml_str(d['t'])}")
        lines.append(f"  class: {base_class}")
        lines.append(f"  class_modifiers:{yaml_list(modifiers, 4)}")
        lines.append(f"  scope:{yaml_list(io.get('scope') or [], 4)}")
        lines.append(f"  owner: {io.get('owner')}")
        lines.append(f"  set_permission: {yaml_str(io['set_permission'])}")
        lines.append(f"  must_be_elected: {'true' if must_be_elected else 'false'}")
        lines.append(f"  default: {yaml_str(default_val)}")
        lines.append(f"  options:{yaml_list(io.get('options') or [], 4)}")
        lines.append(f"  bounds: {yaml_str(io.get('bounds'))}")
        lines.append(f"  status: {status}")
        lines.append(f"  status_note: {yaml_str(status_note)}")
        lines.append(f"  governs:{yaml_list(io.get('governs') or [], 4)}")
        lines.append(f"  form_dependence: {yaml_str(FORM_DEPENDENCE.get(vp_id))}")
        lines.extend(render_catalogue(io.get("catalogue")))
    return "\n".join(lines) + "\n"


def committed_body(vendored_text: str) -> str:
    marker = "variation_points:\n"
    idx = vendored_text.find(marker)
    if idx == -1:
        print("check-drift: could not find 'variation_points:' in the vendored file.", file=sys.stderr)
        sys.exit(1)
    return vendored_text[idx + len(marker):]


def main() -> int:
    args = [a for a in sys.argv[1:] if a != "--write"]
    write = "--write" in sys.argv[1:]
    vendored_path = Path(args[0] if args else "catalogue/variation-points/variation-points.yaml")
    vendored_text = vendored_path.read_text()

    import kdb  # deferred: only present once ~/projects/invoice/tooling/k.sh is sourced

    rows = kdb.q(QUERY)
    try:
        rows = rows + derive_parameter_rows(rows, fetch_published)
    except DerivationError as e:
        print(f"check-drift: cannot derive installation-parameter rows (INV-Q-3202, P57): {e}", file=sys.stderr)
        return 2
    try:
        rows = resolve_set_permissions(rows)
    except SetPermissionError as e:
        print(f"check-drift: {e} (INV-T-10133)", file=sys.stderr)
        return 2
    fresh_body = render_body(rows)
    old_body = committed_body(vendored_text)

    if write:
        marker = "variation_points:\n"
        head = vendored_text[: vendored_text.find(marker) + len(marker)]
        vendored_path.write_text(head + fresh_body)
        print(f"check-drift: re-vendored {vendored_path} from live Keel ({len(rows)} variation points).")
        return 0

    if fresh_body == old_body:
        print(f"check-drift: no drift — {vendored_path} matches live Keel ({len(rows)} variation points).")
        return 0

    diff = difflib.unified_diff(
        old_body.splitlines(keepends=True),
        fresh_body.splitlines(keepends=True),
        fromfile=str(vendored_path),
        tofile="live Keel INV",
    )
    print("check-drift: DRIFT DETECTED — re-vendor variation-points.yaml in its own merge request (AGT-11):")
    sys.stdout.writelines(diff)
    return 1


if __name__ == "__main__":
    sys.exit(main())
