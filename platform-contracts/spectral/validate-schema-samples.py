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
import fnmatch
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
        if not ref.startswith("#/"):
            raise Invalid(f"unsupported $ref (this validator only resolves local '#/...' pointers): {ref}")
        return _resolve(_pointer(root, ref), root)
    return schema


def _pointer(doc, ref: str):
    """Follow a local JSON pointer ('#/components/schemas/X', '#/$defs/y') in doc."""
    node = doc
    for part in ref[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or part not in node:
            raise Invalid(f"$ref {ref} does not resolve")
        node = node[part]
    return node


def load_schema(spec: str) -> tuple[dict, dict]:
    """(schema, root) for '<file>' or '<file>#/<pointer>'. A .yaml/.yml file (an OpenAPI
    document, so request and response schemas are tested where they are defined, never as
    a copy) needs PyYAML; its local '#/components/...' $refs resolve against the document."""
    file_part, _, pointer = spec.partition("#")
    path = Path(file_part)
    if path.suffix in (".yaml", ".yml"):
        import yaml  # PyYAML, as in the other spectral checks
        root = yaml.safe_load(path.read_text())
    else:
        root = json.loads(path.read_text())
    return (_pointer(root, "#" + pointer) if pointer else root), root


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


def validate(instance, schema: dict, root: dict | None = None) -> None:
    _check(instance, schema, schema if root is None else root, "$")


def run_dir(schema_spec: str, samples_dir: Path, pattern: str | None = None,
            exclude: tuple[str, ...] = ()) -> list[str]:
    """Run one schema against the samples of samples_dir. pattern (a filename glob) limits it to
    the samples it claims; exclude lists the globs other schemas of the same directory claim,
    so a schema with no glob tests only the samples nobody else claims."""
    failures: list[str] = []
    schema, root = load_schema(schema_spec)

    def mine(sample: Path) -> bool:
        if pattern is not None:
            return fnmatch.fnmatch(sample.name, pattern)
        return not any(fnmatch.fnmatch(sample.name, g) for g in exclude)

    for sample in sorted((samples_dir / "valid").glob("*.json")):
        if not mine(sample):
            continue
        instance = json.loads(sample.read_text())
        try:
            validate(instance, schema, root)
        except Invalid as e:
            failures.append(f"{sample}: expected VALID but failed: {e}")

    for sample in sorted((samples_dir / "invalid").glob("*.json")):
        if not mine(sample):
            continue
        instance = json.loads(sample.read_text())
        try:
            validate(instance, schema, root)
            failures.append(f"{sample}: expected INVALID but passed")
        except Invalid:
            pass

    return failures


def discover(root: Path) -> list[tuple[str, Path, str | None, tuple[str, ...]]] | str:
    """(schema spec, examples-dir, sample glob, other schemas' globs) for every <root>/*/examples;
    an error string if one is unusable.

    schema.txt holds one line per schema: '<schema>' or '<schema> <sample-filename-glob>'. <schema> is
    a path relative to <service>/examples, optionally '<file>#/<pointer>' (an OpenAPI schema, so a
    request or response schema is tested where it is defined). A line with a glob tests the samples
    whose file name matches it; a line without one tests the samples no glob claims. A sample that
    no line claims fails."""
    pairs = []
    for ex in sorted(root.glob("*/examples")):
        if not ((ex / "valid").is_dir() or (ex / "invalid").is_dir()):
            continue
        decl = ex / "schema.txt"
        if not decl.is_file():
            return f"{ex} has valid/ or invalid/ samples but no schema.txt naming the schema they test"
        lines = [ln.split() for ln in decl.read_text().splitlines() if ln.strip()]
        if not lines or any(len(ln) > 2 for ln in lines):
            return f"{decl} must hold lines of '<schema>' or '<schema> <sample-filename-glob>'"
        globs = tuple(ln[1] for ln in lines if len(ln) == 2)
        if sum(1 for ln in lines if len(ln) == 1) > 1:
            return f"{decl} names more than one schema without a sample glob"
        for ln in lines:
            file_part = ln[0].partition("#")[0]
            schema = (ex / file_part).resolve()
            if not schema.is_file():
                return f"{decl} names {schema}, which does not exist"
            spec = str(schema) + ln[0][len(file_part):]
            pairs.append((spec, ex, ln[1] if len(ln) == 2 else None, globs if len(ln) == 1 else ()))
        if all(len(ln) == 2 for ln in lines):
            for kind in ("valid", "invalid"):
                for sample in sorted((ex / kind).glob("*.json")):
                    if not any(fnmatch.fnmatch(sample.name, g) for g in globs):
                        return f"{sample} matches no sample glob of {decl}"
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
        runs = found
    else:
        if len(argv) < 3 or len(argv) % 2 != 1:
            print(__doc__)
            return 2
        runs = [(a, Path(b), None, ()) for a, b in zip(argv[1::2], argv[2::2])]
    all_failures: list[str] = []
    for schema_spec, d, pattern, exclude in runs:
        failures = run_dir(schema_spec, d, pattern, exclude)
        if failures:
            print(f"gst-ci: {schema_spec} against {d} — FAILED:")
            for f in failures:
                print(f"  - {f}")
            all_failures.extend(failures)
        else:
            def n(kind: str) -> int:
                files = (d / kind).glob("*.json")
                if pattern is not None:
                    return sum(1 for f in files if fnmatch.fnmatch(f.name, pattern))
                return sum(1 for f in files if not any(fnmatch.fnmatch(f.name, g) for g in exclude))
            print(f"gst-ci: {schema_spec} — {n('valid')} valid + {n('invalid')} invalid samples in {d} all as expected.")
    return 1 if all_failures else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
