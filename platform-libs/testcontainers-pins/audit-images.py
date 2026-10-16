#!/usr/bin/env python3
"""INV-T-5604 audit: every container image literal in the Kotlin/Java sources must be a key of ImagePins or the one
already digest-pinned MinIO reference. Deny by default: an image literal outside that set exits 1.

Run from the repository root:  python3 testcontainers-pins/audit-images.py

A literal is read from the arguments of DockerImageName.parse(...), of any PostgreSQLContainer(...) call or
subclass constructor argument, from @ValueSource(...) (only strings shaped like an image reference there) and from the
initialiser of a constant or value whose name contains IMAGE. Tracked and untracked (not ignored) files are scanned.
"""
import re
import subprocess
import sys

PINS = "testcontainers-pins/src/main/kotlin/com/dynokore/platform/testcontainerspins/ImagePins.kt"
# The MinIO image evidence-store's S3ObjectStoreMinioIT already pins by digest: allowed by exact equality.
MINIO = "cgr.dev/chainguard/minio@sha256:bd014394a80898e68c149f2311fdf8d5a2c2f3bb2c33b9327ae6d02b4b065ae1"
IMAGE_SHAPE = re.compile(r"^[a-z0-9][a-z0-9._-]*(/[a-z0-9._-]+)*(:[A-Za-z0-9._-]+)?(@sha256:[0-9a-f]{64})?$")
TAGGED = re.compile(r"^[a-z0-9][a-z0-9._-]*(/[a-z0-9._-]+)*:[A-Za-z][A-Za-z0-9._-]*$|^[a-z0-9][a-z0-9._-]*(/[a-z0-9._-]+)*:[0-9][A-Za-z0-9._-]*$|@sha256:")
STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
CALLS = [
    (re.compile(r"DockerImageName\.parse\s*\("), False),
    (re.compile(r"PostgreSQLContainer\s*(<[^>(]*>)?\s*\("), False),
    (re.compile(r"@ValueSource\s*\("), True),
]
CONSTANT = re.compile(r"\b\w*IMAGE\w*\s*(:\s*[\w.<>?]+\s*)?=\s*")


def span(text, start):
    """Text from the open parenthesis at start-1 to its match."""
    depth, i = 1, start
    while i < len(text) and depth:
        depth += {"(": 1, ")": -1}.get(text[i], 0)
        i += 1
    return text[start:i]


def literals(text):
    found = []
    for pattern, shaped_only in CALLS:
        for m in pattern.finditer(text):
            for s in STRING.findall(span(text, m.end())):
                if not shaped_only or (IMAGE_SHAPE.match(s) and TAGGED.search(s)):
                    found.append(s)
    for m in CONSTANT.finditer(text):
        first = STRING.match(text, len(text) - len(text[m.end():].lstrip()))
        if first:
            found.append(first.group(1))
    return found


def main():
    keys = set(re.findall(r'^\s*"(postgres:\d+)" to ', open(PINS).read(), re.M))
    allowed = keys | {MINIO}
    files = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", "--", "*.kt", "*.java"], capture_output=True, text=True, check=True
    ).stdout.split()
    seen, bad = {}, {}
    for f in files:
        if f.startswith("testcontainers-pins/"):  # the pin module itself holds the table and the refused look-alikes
            continue
        for s in literals(open(f, encoding="utf-8").read()):
            seen.setdefault(s, set()).add(f)
            if s not in allowed:
                bad.setdefault(s, set()).add(f)
    print("table keys: " + ", ".join(sorted(keys)))
    print("distinct image literals: " + ", ".join(sorted(seen)))
    for s, fs in sorted(bad.items()):
        print(f"AUDIT FAIL: '{s}' is not a pinned key ({', '.join(sorted(fs))})")
    if not keys or not seen:
        print("AUDIT FAIL: nothing to compare (empty table or no literal found)")
        return 1
    return 1 if bad else 0


sys.exit(main())
