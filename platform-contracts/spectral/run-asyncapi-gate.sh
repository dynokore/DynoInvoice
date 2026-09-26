#!/usr/bin/env sh
# spectral/run-asyncapi-gate.sh — INV-T-2849 (T5), API-03, API-41
#
# For every directory's asyncapi.yaml that already existed at the merge
# request's base commit, fails on a breaking structural change (a removed
# channel/operation/message, a changed address, …) via the AsyncAPI CLI's own
# `diff -t breaking`. Payload-level compatibility is a separate, additive-only
# check (run-oasdiff-gate.sh's check-payload-compat.py) — the AsyncAPI CLI does
# not itself deeply diff payload schemas.
#
# Usage: run-asyncapi-gate.sh <base-sha>
set -u
base_sha="$1"
failed=0

for dir in */; do
  svc="${dir%/}"
  [ "$svc" = "spectral" ] && continue

  if [ -f "$svc/asyncapi.yaml" ] && git cat-file -e "$base_sha:$svc/asyncapi.yaml" 2>/dev/null; then
    git show "$base_sha:$svc/asyncapi.yaml" > /tmp/base-asyncapi.yaml
    echo "gst-ci: asyncapi diff $svc/asyncapi.yaml against $base_sha"
    breaking=$(asyncapi diff /tmp/base-asyncapi.yaml "$svc/asyncapi.yaml" -t breaking -f json 2>/dev/null)
    if [ "$breaking" != "[]" ] && [ -n "$breaking" ]; then
      echo "gst-ci: breaking AsyncAPI structural change in $svc/asyncapi.yaml:"
      echo "$breaking"
      failed=1
    fi
  fi
done

exit $failed
