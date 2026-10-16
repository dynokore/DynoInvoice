#!/usr/bin/env python3
"""INV-T-4915 (INV-Q-5509): the page bound of PackDryRunSampleRecorded, checked fail-closed.

Loads service-runtime/schemas/pack-dry-run-sample-recorded-payload.schema.json and compares by equality:
  (a) properties.documents.maxItems is the integer 500;
  (b) the top-level description contains 'API-42' and '1 MB';
  (c) the valid full-page sample has len(documents) == maxItems, validates, and is under 1,000,000 bytes;
  (d) the invalid over-max sample has len(documents) == maxItems + 1, `validate` raises Invalid for it with a
      message that names maxItems and path $.documents, and the same document cut to its first maxItems
      entries validates (so maxItems is the only violation);
  (e) both samples are registered in examples/schema.txt against this schema.
Prints one line per failed item and exits 1; a file that cannot be loaded exits 2. Usage: check-page-bound.py [ROOT]
"""
import fnmatch
import importlib.util
import json
import sys
from pathlib import Path


SCHEMA = "service-runtime/schemas/pack-dry-run-sample-recorded-payload.schema.json"
VALID = "service-runtime/examples/valid/pack-dry-run-sample-recorded-payload-full-page.json"
INVALID = "service-runtime/examples/invalid/pack-dry-run-sample-recorded-payload-page-over-max-items.json"
SCHEMA_REF = "../schemas/pack-dry-run-sample-recorded-payload.schema.json"
REGISTRY = "service-runtime/examples/schema.txt"
MAX_ITEMS = 500
EVENT_LIMIT_BYTES = 1_000_000


def _load_validator():
    """The stdlib-only validator of schema-sample-validation (spectral/validate-schema-samples.py)."""
    path = Path(__file__).resolve().parent.parent / "spectral" / "validate-schema-samples.py"
    spec = importlib.util.spec_from_file_location("validate_schema_samples", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check(root: Path) -> list[str]:
    schema = json.loads((root / SCHEMA).read_text())
    valid_bytes = (root / VALID).read_bytes()
    valid = json.loads(valid_bytes)
    invalid = json.loads((root / INVALID).read_text())
    registry = (root / REGISTRY).read_text().splitlines()
    failures: list[str] = []

    docs = schema.get("properties", {}).get("documents", {})
    max_items = docs.get("maxItems") if isinstance(docs, dict) else None
    if type(max_items) is not int or max_items != MAX_ITEMS:
        failures.append(f"(a) properties.documents.maxItems is {max_items!r}, expected {MAX_ITEMS}")
    description = schema.get("description")
    for needle in ("API-42", "1 MB"):
        if not isinstance(description, str) or needle not in description:
            failures.append(f"(b) schema description lacks {needle!r}")

    vss = _load_validator()
    bound = max_items if type(max_items) is int else MAX_ITEMS
    valid_docs = valid.get("documents") if isinstance(valid, dict) else None
    if not isinstance(valid_docs, list) or len(valid_docs) != bound:
        failures.append(f"(c) valid sample has {len(valid_docs) if isinstance(valid_docs, list) else valid_docs!r} documents, expected {bound}")
    try:
        vss.validate(valid, schema)
    except vss.Invalid as e:
        failures.append(f"(c) valid sample fails the schema: {str(e)[:120]}")
    if len(valid_bytes) >= EVENT_LIMIT_BYTES:
        failures.append(f"(c) valid sample is {len(valid_bytes)} bytes, not under {EVENT_LIMIT_BYTES}")

    invalid_docs = invalid.get("documents") if isinstance(invalid, dict) else None
    if not isinstance(invalid_docs, list) or len(invalid_docs) != bound + 1:
        failures.append(f"(d) invalid sample has {len(invalid_docs) if isinstance(invalid_docs, list) else invalid_docs!r} documents, expected {bound + 1}")
    try:
        vss.validate(invalid, schema)
        failures.append("(d) invalid sample validates, expected an Invalid naming maxItems at $.documents")
    except vss.Invalid as e:
        if "maxItems" not in str(e) or not str(e).startswith("$.documents:"):
            failures.append(f"(d) invalid sample fails for another reason: {str(e)[:120]}")
    if isinstance(invalid_docs, list):
        try:
            vss.validate(dict(invalid, documents=invalid_docs[:bound]), schema)
        except vss.Invalid as e:
            failures.append(f"(d) invalid sample cut to {bound} documents still fails (maxItems is not the only violation): {str(e)[:120]}")

    registered = [parts[1] for parts in (line.split() for line in registry) if len(parts) == 2 and parts[0] == SCHEMA_REF]
    for sample in (VALID, INVALID):
        if not any(fnmatch.fnmatchcase(Path(sample).name, pattern) for pattern in registered):
            failures.append(f"(e) {sample} is not registered in schema.txt against {SCHEMA_REF}")
    return failures


def main(argv: list[str]) -> int:
    root = Path(argv[1] if len(argv) > 1 else ".")
    try:
        failures = check(root)
    except (OSError, ValueError, ImportError, AttributeError) as e:
        print(f"gst-ci: page-bound check cannot load a file: {e}")
        return 2
    for f in failures:
        print(f"gst-ci: page-bound FAIL {f}")
    if not failures:
        print("gst-ci: page-bound OK (maxItems 500, API-42 1 MB cited, boundary samples 500 valid / 501 invalid)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
