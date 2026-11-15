#!/usr/bin/env sh
# spectral/self-test-asyncapi-exceptions.sh — INV-T-23170
# Proves filter-asyncapi-exceptions.py and run-asyncapi-gate.sh fail closed (lesson P20), against fixtures.
# Needs python3 and git. The gate is run with a stub `asyncapi` that prints fixtures/asyncapi-exceptions/breaking.json
# (the real CLI output shape, recorded from the pinned ASYNCAPI_CLI_IMAGE).
set -u
cd "$(dirname "$0")"
failed=0
F=fixtures/asyncapi-exceptions
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
: > "$work/empty.txt"
printf '# Fixture. Owner ruling 2026-11-15.\n{not json\n' > "$work/malformed.txt"
printf '# Fixture. Owner ruling 2026-11-15.\n{"action":"remove","before":{"address":"gst.*"}}\n' > "$work/embedded-glob.txt"
printf '# a comment without any ruling key or date\n%s\n' "$(sed 1d "$F/listed.txt")" > "$work/noruling.txt"

expect() { # <name> <expected-code> <input-file> <exceptions-file>
  python3 -I filter-asyncapi-exceptions.py "$4" < "$3" > "$work/out.txt" 2>&1
  code=$?
  if [ "$code" -ne "$2" ]; then
    echo "gst-ci: FAIL — $1: exit $code, expected $2"; cat "$work/out.txt"; failed=1
  else
    echo "gst-ci: ok — $1 (exit $code)"
  fi
}
echo '[]' > "$work/none.json"
expect "empty list, one breaking entry" 1 "$F/breaking.json" "$work/empty.txt"
grep -q 'rule-pack-activated' "$work/out.txt" || { echo "gst-ci: FAIL — the unlisted entry is not named"; failed=1; }
expect "listed.txt" 0 "$F/breaking.json" "$F/listed.txt"
expect "lookalike.txt" 1 "$F/breaking.json" "$F/lookalike.txt"
expect "uncommented.txt" 1 "$F/breaking.json" "$F/uncommented.txt"
expect "glob.txt" 1 "$F/breaking.json" "$F/glob.txt"
expect "embedded wildcard" 1 "$F/breaking.json" "$work/embedded-glob.txt"
expect "malformed line" 1 "$F/breaking.json" "$work/malformed.txt"
expect "comment without ruling" 1 "$F/breaking.json" "$work/noruling.txt"
expect "[] with empty list" 0 "$work/none.json" "$work/empty.txt"
expect "the committed list is comment-only" 0 "$work/none.json" asyncapi-exceptions.txt
if grep -v '^#' asyncapi-exceptions.txt | grep -q .; then echo "gst-ci: FAIL — asyncapi-exceptions.txt has a non-comment line"; failed=1; fi
printf 'not json' > "$work/bad.json"
expect "input that is not JSON" 1 "$work/bad.json" "$work/empty.txt"

# Gate: a repository with one service whose channel is removed at head.
mkdir "$work/bin" "$work/repo"
printf '#!/bin/sh\ncat "%s/%s/breaking.json"\nexit 1\n' "$PWD" "$F" > "$work/bin/asyncapi"
chmod +x "$work/bin/asyncapi"
here="$PWD"
(
  cd "$work/repo" && git init -q . && mkdir svc && echo 'asyncapi: 3.0.0' > svc/asyncapi.yaml &&
  git add . && git -c user.name=t -c user.email=t@t commit -qm base
) || { echo "gst-ci: FAIL — fixture repository"; exit 1; }
base="$(git -C "$work/repo" rev-parse HEAD)"
gate() { # <name> <expected-code> <exceptions-file>
  (cd "$work/repo" && PATH="$work/bin:$PATH" sh "$here/run-asyncapi-gate.sh" "$base" "$3") > "$work/out.txt" 2>&1
  code=$?
  if [ "$code" -ne "$2" ]; then echo "gst-ci: FAIL — gate $1: exit $code, expected $2"; cat "$work/out.txt"; failed=1
  else echo "gst-ci: ok — gate $1 (exit $code)"; fi
}
gate "removed channel, empty exception file" 1 "$work/empty.txt"
gate "removed channel, listed.txt" 0 "$here/$F/listed.txt"
gate "removed channel, lookalike.txt" 1 "$here/$F/lookalike.txt"
if grep -q '"\$breaking" != "\[\]"' run-asyncapi-gate.sh; then echo "gst-ci: FAIL — the gate still compares with []"; failed=1; fi
grep -q 'filter-asyncapi-exceptions.py' run-asyncapi-gate.sh || { echo "gst-ci: FAIL — the gate does not use the filter"; failed=1; }
exit $failed
