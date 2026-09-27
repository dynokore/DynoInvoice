#!/usr/bin/env python3
"""spectral/strip-scaffold-placeholder.py — INV-T-2850 follow-up (T6)

Run by run-oasdiff-gate.sh against its own BASE-commit copy of a service's
openapi.yaml, never against HEAD.

Every service directory T5 (INV-T-2849) scaffolded got the exact same single
placeholder operation, byte-identical across all fifteen directories but for
the service name substitution:

  paths:
    /v1/<svc>/examples:
      post:
        operationId: createExample
        summary: Placeholder operation (INV-T-2849 scaffold) — replace with a real one
        ...

The first real contract merge request for a directory deletes that whole
path, which oasdiff (correctly, in general) reports as a breaking
`api-path-removed-without-deprecation` — deprecating it first is not viable,
since it is not a real operation any generated client calls. This script
deletes ONLY that exact operation from the BASE copy before the diff runs,
so its removal is never compared at all (not "waived after being flagged" —
oasdiff never sees it as present in the base to begin with). Every other
path and operation is left exactly as-is, so removing anything else still
fails the gate (CS-50).

Identification is by exact match, not a broad pattern, on properties T5
itself generated and that a real operation would not plausibly carry:
  - the path key is exactly "/v1/<svc>/examples" for the directory's own
    service name (never a generic regex over all paths);
  - it has exactly one HTTP method, "post";
  - that operation's operationId is exactly "createExample";
  - its summary carries the literal marker T5 already wrote on every one
    of them, the substring "INV-T-2849 scaffold".

All four must hold, or nothing is touched — a path that merely reuses the
same URL, or a "createExample" operationId elsewhere, is never exempted; nor
is a removal anywhere else in the file. Because this only ever runs on the
BASE copy, a HEAD version that happens to introduce something shaped like
this marker earns no exemption for anything: this script never looks at
HEAD, and a fresh HEAD-only path is not a *removal* oasdiff would flag.

Usage: strip-scaffold-placeholder.py <svc> <path-to-base-openapi-yaml-copy>
Rewrites the file in place only if the exact placeholder block is found.
Exits 0 always (nothing to find is not an error: most services no longer
carry the scaffold once their real contract lands).
"""
import re
import sys

PLACEHOLDER_MARKER = "INV-T-2849 scaffold"


def _is_exact_t5_placeholder(block_text: str) -> bool:
    method_lines = re.findall(r"^    \S.*$", block_text, re.M)
    if method_lines != ["    post:"]:
        return False
    if re.search(r"^      operationId:\s*createExample\s*$", block_text, re.M) is None:
        return False
    if PLACEHOLDER_MARKER not in block_text:
        return False
    return True


def strip_placeholder(svc: str, text: str) -> tuple[str, bool]:
    path_key_line = f"  /v1/{svc}/examples:"
    lines = text.split("\n")
    for i, line in enumerate(lines):
        if line != path_key_line:
            continue
        # The block runs from the path key to the next line indented <= 2
        # spaces (the next sibling path, or the next top-level key such as
        # "components:"), or to end of file.
        j = i + 1
        while j < len(lines):
            candidate = lines[j]
            if candidate.strip() == "":
                j += 1
                continue
            indent = len(candidate) - len(candidate.lstrip(" "))
            if indent <= 2:
                break
            j += 1
        block_text = "\n".join(lines[i:j])
        if _is_exact_t5_placeholder(block_text):
            return "\n".join(lines[:i] + lines[j:]), True
        # The path exists but is not an exact match for the T5 scaffold
        # shape (e.g. it has been edited into a real operation, or grew a
        # second method) — never exempt it.
        return text, False
    return text, False


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: strip-scaffold-placeholder.py <svc> <file>", file=sys.stderr)
        return 2
    svc, path = sys.argv[1], sys.argv[2]
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    stripped, matched = strip_placeholder(svc, text)
    if matched:
        with open(path, "w", encoding="utf-8") as f:
            f.write(stripped)
        print(
            f"gst-ci: base {svc}/openapi.yaml still carries the T5 scaffold "
            f"placeholder (INV-T-2849) at /v1/{svc}/examples — its removal "
            f"is exempted from the breaking-change check (INV-T-2850 follow-up, CS-50 "
            f"still applies to every other path)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
