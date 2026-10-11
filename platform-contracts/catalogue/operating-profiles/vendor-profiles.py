#!/usr/bin/env python3
"""vendor-profiles.py — INV-T-3277 (FU-C2.3).

Re-extracts the Keel INV operating-profile rows PRF-1..PRF-7 (INV-REQ-1252..1258) with
the read-only query below (the same one recorded in operating-profiles.yaml's header)
and reports whether the committed, vendored snapshot has drifted from live Keel.

Each row's `profile` block is Keel's `attrs.io_contract.profile` (version, status,
values; rulings INV-Q-3283 A and INV-Q-3285 A), copied unedited. `name` is the profile
name Keel's own row text gives after `#row:PRF-n` (INV-NOTE-3 §37.4); nothing is mapped
by hand.

Usage:
    source ~/projects/invoice/tooling/k.sh
    python3 catalogue/operating-profiles/vendor-profiles.py [--write] [path/to/operating-profiles.yaml]

--write re-vendors: it first copies the committed snapshot to previous-snapshot.yaml
(only when the data below the header changes), then rewrites everything below the
header from live Keel. The generator compares the two and fails when a profile's values
changed without a higher version (INV-Q-3283), and emits the per-profile difference
(INV-REQ-1245 b4). Exit 0: no drift (or re-vendored). Exit 1: drift (diff printed).

Not CI-wired, for the same reason as catalogue/variation-points/check-drift.py: no CI
job can reach Keel's read-only database yet.
"""
from __future__ import annotations

import difflib
import re
import sys
from pathlib import Path

QUERY = """
select json_agg(json_build_object(
    'k', e.key, 'src', e.source_ids, 'rev', r.id, 't', r.title, 'body', r.body,
    'profile', r.attrs->'io_contract'->'profile'))
from entity e join entity_revision r on r.entity_id = e.id
  and r.published_in is not null and r.superseded_in is null
where e.project_id = (select id from project where code = 'INV')
  and e.type = 'requirement' and e.source_ids::text like '%PRF-%'
"""

MARKER = "operating_profiles:\n"

PROFILE_ORDER = ["version", "status", "note", "basis", "values"]
VALUE_ORDER = [
    "requirement", "text", "option", "options", "typed", "typed_value", "condition", "recommended",
    "otherwise_option", "alternatives", "alternative_by", "adds", "at_apply", "explicit_default",
    "needs_acknowledgement", "untyped_reason", "condition_fact",
]
TYPED_ORDER = ["type", "unit", "value"]
CONDITION_FACT_ORDER = ["constraint", "holder", "name"]  # INV-T-3636, INV-Q-3692


def prf_num(src: list[str]) -> int:
    for s in src:
        m = re.match(r"PRF-(\d+)$", s)
        if m:
            return int(m.group(1))
    raise SystemExit(f"vendor-profiles: no PRF-n source id in {src}")


def vp_num(key: str) -> int:
    m = re.match(r"VP-(\d+)$", key)
    if not m:
        raise SystemExit(f"vendor-profiles: profile value key '{key}' is not of the form VP-nn")
    return int(m.group(1))


def yaml_str(s) -> str:
    escaped = str(s).replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def yaml_scalar(v) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    return yaml_str(v)


def ordered_keys(d: dict, order: list[str]) -> list[str]:
    return [k for k in order if k in d] + sorted(k for k in d if k not in order)


def node(v, indent: int, order: list[str]) -> list[str]:
    pad = " " * indent
    out = []
    if isinstance(v, dict):
        for k in ordered_keys(v, order):
            val = v[k]
            sub = TYPED_ORDER if k == "typed_value" else CONDITION_FACT_ORDER if k == "condition_fact" else []
            if isinstance(val, (dict, list)) and val:
                out.append(f"{pad}{k}:")
                out.extend(node(val, indent + 2, sub))
            elif isinstance(val, (dict, list)):
                out.append(f"{pad}{k}: {'{}' if isinstance(val, dict) else '[]'}")
            else:
                out.append(f"{pad}{k}: {yaml_scalar(val)}")
    else:
        for item in v:
            if isinstance(item, (dict, list)):
                inner = node(item, indent + 2, order)
                out.append(f"{pad}- {inner[0].lstrip()}")
                out.extend(inner[1:])
            else:
                out.append(f"{pad}- {yaml_scalar(item)}")
    return out


def profile_name(prf: str, body: str) -> str:
    m = re.search(r"#row:" + re.escape(prf) + r" ([^*|]+?)\*\*", body or "")
    if not m:
        raise SystemExit(f"vendor-profiles: no '#row:{prf} <name>' in the Keel row text of {prf}")
    return m.group(1).strip()


def render_body(rows: list[dict]) -> str:
    lines = []
    for d in sorted(rows, key=lambda x: prf_num(x["src"])):
        prf = f"PRF-{prf_num(d['src'])}"
        p = d["profile"]
        if not p:
            raise SystemExit(f"vendor-profiles: {d['k']} ({prf}) has no io_contract.profile in Keel")
        lines.append(f"- id: {prf}")
        lines.append(f"  requirement: {d['k']}")
        lines.append(f"  revision: {d['rev']}")
        lines.append(f"  name: {yaml_str(profile_name(prf, d['body']))}")
        lines.append(f"  title: {yaml_str(d['t'])}")
        lines.append("  profile:")
        rest = {k: v for k, v in p.items() if k != "values"}
        lines.extend(node(rest, 4, PROFILE_ORDER))
        values = p.get("values") or {}
        if not values:
            lines.append("    values: {}")
            continue
        lines.append("    values:")
        for vp in sorted(values, key=vp_num):
            lines.append(f"      {vp}:")
            lines.extend(node(values[vp], 8, VALUE_ORDER))
    return "\n".join(lines) + "\n"


def split(text: str) -> tuple[str, str]:
    idx = text.find(MARKER)
    if idx == -1:
        print(f"vendor-profiles: no '{MARKER.strip()}' line in the vendored file.", file=sys.stderr)
        sys.exit(1)
    return text[: idx + len(MARKER)], text[idx + len(MARKER):]


def main() -> int:
    args = [a for a in sys.argv[1:] if a != "--write"]
    write = "--write" in sys.argv[1:]
    path = Path(args[0] if args else "catalogue/operating-profiles/operating-profiles.yaml")
    head, old_body = split(path.read_text())

    import kdb  # deferred: present once ~/projects/invoice/tooling/k.sh is sourced

    rows = kdb.q(QUERY)
    fresh = render_body(rows)
    if fresh == old_body:
        print(f"vendor-profiles: no drift — {path} matches live Keel ({len(rows)} profiles).")
        return 0
    if write:
        if old_body.strip():
            prev = path.with_name("previous-snapshot.yaml")
            prev.write_text(
                "# The operating-profiles.yaml data this re-vendor replaced (vendor-profiles.py --write,\n"
                "# INV-T-3277): the generator's 'previous snapshot' for the version check (INV-Q-3283)\n"
                "# and the per-profile difference (INV-REQ-1245 b4). Never hand-edited.\n" + MARKER + old_body
            )
        path.write_text(head + fresh)
        print(f"vendor-profiles: re-vendored {path} from live Keel ({len(rows)} profiles).")
        return 0
    print("vendor-profiles: DRIFT DETECTED — re-vendor operating-profiles.yaml in its own merge request (AGT-11):")
    sys.stdout.writelines(difflib.unified_diff(
        old_body.splitlines(keepends=True), fresh.splitlines(keepends=True),
        fromfile=str(path), tofile="live Keel INV"))
    return 1


if __name__ == "__main__":
    sys.exit(main())
