#!/usr/bin/env python3
"""submission/schemas/generate-lifecycle-states.py — INV-T-2859 (T15)

Generates `lifecycle-states.schema.json` from the design source of truth,
`invoice-docs/model/architecture/lifecycle-states.yaml` (SDS §33.2; live
requirement INV-REQ-570 / INV-NOTE-3#def:FR-AE.9), so the wire contract can
never silently drift from the design record. Dependency-free (stdlib only,
mirrors check-payload-compat.py and validate-schema-samples.py's style —
no pip install in CI): `lifecycle-states.yaml` uses only the small, regular
subset of YAML block-mapping-list syntax this parser understands (a top-level
`states:` list of mappings with scalar values, some folded across
continuation lines); it is not a general YAML parser.

Two modes:
    generate-lifecycle-states.py <lifecycle-states.yaml> --write <out.schema.json>
        Regenerates the schema file from the source YAML.
    generate-lifecycle-states.py <lifecycle-states.yaml> --check <existing.schema.json>
        Regenerates in memory and compares byte-for-byte with the existing
        file. Exits 0 if they match (no drift), 1 and a message otherwise.
        This is the CI drift-check job's script.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

SCHEMA_ID = "https://platform-contracts.invoice.internal/submission/schemas/lifecycle-states.schema.json"

_BOOL_VALUES = {"true": True, "false": False}


def _unquote(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == "'" and value[-1] == "'":
        return value[1:-1].replace("''", "'")
    return value


def parse_lifecycle_states(text: str) -> list[dict]:
    """Parses the `states:` list of `lifecycle-states.yaml` into records.

    Each record is one YAML list item (`- level: ...` through the next
    `- level:` or end of file); a `key: value` line starts a field, and a
    more-indented line with no `key:` continues (folds into, joined by a
    single space) the previous field's value — the plain-scalar folding the
    source file actually uses (only the `meaning` field wraps today).
    """
    lines = text.splitlines()
    records: list[dict] = []
    current: dict | None = None
    current_key: str | None = None

    for raw_line in lines:
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        stripped = raw_line.strip()

        if not raw_line.startswith(" ") and not stripped.startswith("- "):
            # A top-level key with no leading indentation and no dash, e.g.
            # the outer `states:` line that opens the list (a YAML block
            # sequence's `- ` items are conventionally written at the same
            # indentation as their parent key, so a dash line is a record,
            # not this structural case). Nothing above document level is a
            # record field.
            current = None
            current_key = None
            continue

        if stripped.startswith("- "):
            current = {}
            records.append(current)
            body = stripped[2:]
            key, _, value = body.partition(":")
            current_key = key.strip()
            current[current_key] = _unquote(value)
            continue

        if raw_line.startswith("  ") and not raw_line.startswith("    ") and ":" in stripped:
            key, _, value = stripped.partition(":")
            current_key = key.strip()
            assert current is not None, "continuation/key line before any '- level:' record"
            current[current_key] = _unquote(value)
            continue

        # A more-indented line with no recognised `key:` prefix folds into
        # the current field (plain-scalar folding).
        assert current is not None and current_key is not None, "orphan continuation line"
        current[current_key] = (current[current_key] + " " + stripped).strip()

    for record in records:
        record["in_flight"] = _BOOL_VALUES.get(str(record.get("in_flight")).lower(), record.get("in_flight"))
    return records


def build_schema(records: list[dict]) -> dict:
    document_states = [r["state"] for r in records if r["level"] == "document"]
    version_states = [r["state"] for r in records if r["level"] == "version"]

    catalogue = [
        {
            "level": r["level"],
            "state": r["state"],
            "meaning": r["meaning"],
            "countsAsUnsubmitted": r["counts_as_unsubmitted"],
            "inFlight": r["in_flight"],
            "terminal": r["terminal"],
        }
        for r in records
    ]

    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": SCHEMA_ID,
        "title": "LifecycleStates",
        "description": (
            "GENERATED — do not hand-edit. Source: invoice-docs "
            "model/architecture/lifecycle-states.yaml (SDS §33.2; live requirement "
            "INV-REQ-570 / INV-NOTE-3#def:FR-AE.9). Regenerate with "
            "generate-lifecycle-states.py --write; the lifecycle-states-drift-check "
            "CI job fails if this file differs from what regeneration produces "
            "(INV-T-2859, T15). The `x-lifecycle-states` catalogue carries each "
            "state's full design metadata (meaning, whether it counts as "
            "unsubmitted for period readiness, whether it is in flight, and its "
            "terminality) verbatim from the source YAML, for a future Kotlin "
            "generator (gst.event-types, T3) the way error-codes.yaml already "
            "feeds the ErrorCode enum (CS-13); `documentLevelState` / "
            "`versionLevelState` / `lifecycleState` are the plain enums a wire "
            "schema (e.g. the obligation register entry, obligation-register-entry."
            "schema.json) references by $ref."
        ),
        "$defs": {
            "documentLevelState": {
                "type": "string",
                "enum": document_states,
                "description": "A document-level lifecycle state (SDS §33.2), before a submittable version exists.",
            },
            "versionLevelState": {
                "type": "string",
                "enum": version_states,
                "description": "A version-level lifecycle state (SDS §33.2), once a payload version exists.",
            },
            "lifecycleState": {
                "description": "Either level's lifecycle state (SDS §33.2) — document-level or version-level.",
                "oneOf": [
                    {"$ref": "#/$defs/documentLevelState"},
                    {"$ref": "#/$defs/versionLevelState"},
                ],
            },
            "stateCatalogueEntry": {
                "type": "object",
                "properties": {
                    "level": {"type": "string", "enum": ["document", "version"]},
                    "state": {"$ref": "#/$defs/lifecycleState"},
                    "meaning": {"type": "string"},
                    "countsAsUnsubmitted": {
                        "type": "string",
                        "description": "Free text (not a plain boolean in the source design record): whether and under what condition this state counts as unsubmitted for period-readiness computation (FR-AE.9).",
                    },
                    "inFlight": {"type": "boolean"},
                    "terminal": {
                        "type": "string",
                        "description": "Free text: 'No', 'Yes', or a condition under which the state stops being terminal (e.g. 'Until amended', 'Until superseded').",
                    },
                },
                "required": ["level", "state", "meaning", "countsAsUnsubmitted", "inFlight", "terminal"],
                "additionalProperties": False,
            },
        },
        "type": "object",
        "properties": {
            "x-lifecycle-states": {
                "type": "array",
                "items": {"$ref": "#/$defs/stateCatalogueEntry"},
            }
        },
        "required": ["x-lifecycle-states"],
        "additionalProperties": True,
        "x-lifecycle-states": catalogue,
    }


def main(argv: list[str]) -> int:
    args = argv[1:]
    if len(args) != 3 or args[1] not in ("--write", "--check"):
        print(__doc__)
        return 2
    yaml_path, mode, other_path = args

    records = parse_lifecycle_states(Path(yaml_path).read_text())
    generated = build_schema(records)
    generated_text = json.dumps(generated, indent=2, ensure_ascii=False) + "\n"

    if mode == "--write":
        Path(other_path).write_text(generated_text)
        print(f"gst-ci: wrote {other_path} from {yaml_path} ({len(records)} states)")
        return 0

    # --check
    existing_text = Path(other_path).read_text()
    if existing_text == generated_text:
        print(f"gst-ci: {other_path} matches {yaml_path} — no drift ({len(records)} states)")
        return 0
    print(f"gst-ci: {other_path} has drifted from {yaml_path} (INV-T-2859) — regenerate with:")
    print(f"  python3 {Path(__file__).name} {yaml_path} --write {other_path}")
    existing_lines = existing_text.splitlines()
    generated_lines = generated_text.splitlines()
    for i, (a, b) in enumerate(zip(existing_lines, generated_lines)):
        if a != b:
            print(f"  first differing line {i + 1}:")
            print(f"    existing:   {a}")
            print(f"    generated:  {b}")
            break
    else:
        print(f"  file length differs: existing {len(existing_lines)} lines, generated {len(generated_lines)} lines")
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
