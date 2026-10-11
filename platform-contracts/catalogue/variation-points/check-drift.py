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

--write (INV-T-3000) re-vendors: it rewrites everything below the file's header from
live Keel instead of diffing. Each row's `catalogue` block (scope levels, option keys,
per-option strictness, typed values, INV-Q-2934, INV-Q-2936) is io_contract.catalogue,
copied unedited; a row Keel has not filled yet renders `catalogue: null`.

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
        lines.append(f"  set_by:{yaml_list(io.get('set_by') or [], 4)}")
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
