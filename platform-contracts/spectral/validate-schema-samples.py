#!/usr/bin/env python3
"""spectral/validate-schema-samples.py — INV-T-2850 (T6)

T6's "Schema validation tests with valid and invalid samples pass": a
minimal, dependency-free JSON Schema (2020-12 subset) validator, covering
exactly the keywords the two envelope schemas use (type, properties,
required, additionalProperties, pattern, format, const, enum, minLength,
minimum, items, local $ref into #/$defs), plus uniqueItems and a calendar
check of format: date (a date matching YYYY-MM-DD must also exist, so
2027-13-45 fails). No pip install in CI (mirrors
check-payload-compat.py's stdlib-only style); networknt json-schema-validator
(STK-74) is the consumer-edge validator once services exist in Kotlin — this
script only proves the two envelope schemas in this repository behave as
their samples expect, at contract-authoring time.

Layout: one pair of args per schema under test — the schema file itself
(read from service-runtime/schemas/ directly; never copied, so samples can
never drift from the schema they claim to test) and a samples directory
holding:
    valid/*.json     samples that MUST validate
    invalid/*.json   samples that MUST fail validation

Usage: validate-schema-samples.py <schema.json> <samples-dir> [<schema.json> <samples-dir> ...]
       validate-schema-samples.py --discover <root>

--discover (INV-T-2850 follow-up, API-04): for every <root>/<service>/examples
directory holding valid/ or invalid/, read <service>/examples/schema.txt (one
line: the schema file, relative to <service>/examples) and run that pair. A
samples directory without schema.txt, or naming a missing schema, fails. This
covers every service's examples/ without listing each one in CI.

Exits 0 if every valid sample passes and every invalid sample fails; 1 otherwise.
"""
from __future__ import annotations

import datetime
import json
import re
import sys
from pathlib import Path

_FORMAT_PATTERNS = {
    "date-time": re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$"),
    "date": re.compile(r"^\d{4}-\d{2}-\d{2}$"),
    "uri": re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:"),
    "uuid": re.compile(
        r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    ),
}


class Invalid(Exception):
    pass


def _resolve(schema: dict, root: dict) -> dict:
    if "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/$defs/"):
            raise Invalid(f"unsupported $ref (this validator only resolves local #/$defs): {ref}")
        return root.get("$defs", {})[ref[len("#/$defs/"):]]
    return schema


def _check(instance, schema: dict, root: dict, path: str) -> None:
    schema = _resolve(schema, root)

    if "const" in schema and instance != schema["const"]:
        raise Invalid(f"{path}: expected const {schema['const']!r}, got {instance!r}")

    if "enum" in schema and instance not in schema["enum"]:
        raise Invalid(f"{path}: {instance!r} not one of {schema['enum']!r}")

    t = schema.get("type")
    if t == "object":
        if not isinstance(instance, dict):
            raise Invalid(f"{path}: expected object, got {type(instance).__name__}")
        for req in schema.get("required", []):
            if req not in instance:
                raise Invalid(f"{path}: missing required property '{req}'")
        props = schema.get("properties", {})
        for key, value in instance.items():
            if key in props:
                _check(value, props[key], root, f"{path}.{key}")
            elif schema.get("additionalProperties") is False:
                raise Invalid(f"{path}: additional property '{key}' not allowed")
    elif t == "array":
        if not isinstance(instance, list):
            raise Invalid(f"{path}: expected array, got {type(instance).__name__}")
        if "minItems" in schema and len(instance) < schema["minItems"]:
            raise Invalid(f"{path}: fewer than minItems {schema['minItems']}")
        if "maxItems" in schema and len(instance) > schema["maxItems"]:
            raise Invalid(f"{path}: more than maxItems {schema['maxItems']}")
        if schema.get("uniqueItems") is True:
            seen = set()
            for i, el in enumerate(instance):
                key = json.dumps(el, sort_keys=True)
                if key in seen:
                    raise Invalid(f"{path}[{i}]: duplicate item {el!r} violates uniqueItems")
                seen.add(key)
        items = schema.get("items")
        if items is not None:
            for i, el in enumerate(instance):
                _check(el, items, root, f"{path}[{i}]")
    elif t == "string":
        if not isinstance(instance, str):
            raise Invalid(f"{path}: expected string, got {type(instance).__name__}")
        if "minLength" in schema and len(instance) < schema["minLength"]:
            raise Invalid(f"{path}: shorter than minLength {schema['minLength']}")
        if "maxLength" in schema and len(instance) > schema["maxLength"]:
            raise Invalid(f"{path}: longer than maxLength {schema['maxLength']}")
        if "pattern" in schema and not re.match(schema["pattern"], instance):
            raise Invalid(f"{path}: {instance!r} does not match pattern {schema['pattern']!r}")
        fmt = schema.get("format")
        if fmt in _FORMAT_PATTERNS and not _FORMAT_PATTERNS[fmt].match(instance):
            raise Invalid(f"{path}: {instance!r} does not match format '{fmt}'")
        if fmt == "date":
            try:
                datetime.date.fromisoformat(instance)
            except ValueError:
                raise Invalid(f"{path}: {instance!r} is not a valid calendar date (format 'date')") from None
    elif t == "integer":
        if not isinstance(instance, int) or isinstance(instance, bool):
            raise Invalid(f"{path}: expected integer, got {type(instance).__name__}")
        if "minimum" in schema and instance < schema["minimum"]:
            raise Invalid(f"{path}: {instance} below minimum {schema['minimum']}")
    elif t == "boolean":
        if not isinstance(instance, bool):
            raise Invalid(f"{path}: expected boolean, got {type(instance).__name__}")
    # t is None (e.g. the envelope's untyped 'data') or another type: no further check.


def validate(instance, schema: dict) -> None:
    _check(instance, schema, schema, "$")


def run_dir(schema_path: Path, samples_dir: Path) -> list[str]:
    failures: list[str] = []
    schema = json.loads(schema_path.read_text())

    for sample in sorted((samples_dir / "valid").glob("*.json")):
        instance = json.loads(sample.read_text())
        try:
            validate(instance, schema)
        except Invalid as e:
            failures.append(f"{sample}: expected VALID but failed: {e}")

    for sample in sorted((samples_dir / "invalid").glob("*.json")):
        instance = json.loads(sample.read_text())
        try:
            validate(instance, schema)
            failures.append(f"{sample}: expected INVALID but passed")
        except Invalid:
            pass

    return failures


def discover(root: Path) -> list[tuple[Path, Path]] | str:
    """(schema, examples-dir) for every <root>/*/examples; an error string if one is unusable."""
    pairs = []
    for ex in sorted(root.glob("*/examples")):
        if not ((ex / "valid").is_dir() or (ex / "invalid").is_dir()):
            continue
        decl = ex / "schema.txt"
        if not decl.is_file():
            return f"{ex} has valid/ or invalid/ samples but no schema.txt naming the schema they test"
        schema = (ex / decl.read_text().strip()).resolve()
        if not schema.is_file():
            return f"{decl} names {schema}, which does not exist"
        pairs.append((schema, ex))
    return pairs


def main(argv: list[str]) -> int:
    if len(argv) == 3 and argv[1] == "--discover":
        found = discover(Path(argv[2]))
        if isinstance(found, str):
            print(f"gst-ci: {found}")
            return 1
        if not found:
            print(f"gst-ci: --discover found no <service>/examples directory under {argv[2]}")
            return 1
        argv = [argv[0]] + [str(x) for pair in found for x in pair]
    if len(argv) < 3 or len(argv) % 2 != 1:
        print(__doc__)
        return 2
    all_failures: list[str] = []
    pairs = list(zip(argv[1::2], argv[2::2]))
    for schema_arg, samples_arg in pairs:
        schema_path, d = Path(schema_arg), Path(samples_arg)
        failures = run_dir(schema_path, d)
        if failures:
            print(f"gst-ci: {schema_path} against {d} — FAILED:")
            for f in failures:
                print(f"  - {f}")
            all_failures.extend(failures)
        else:
            n_valid = len(list((d / "valid").glob("*.json")))
            n_invalid = len(list((d / "invalid").glob("*.json")))
            print(f"gst-ci: {schema_path} — {n_valid} valid + {n_invalid} invalid samples in {d} all as expected.")
    return 1 if all_failures else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
