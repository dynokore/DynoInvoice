#!/usr/bin/env sh
# spectral/run-oasdiff-gate.sh — INV-T-2849 (T5), API-03, API-41
#
# For every directory's openapi.yaml and schemas/*.schema.json that already
# existed at the merge request's base commit, fails on a breaking change:
# oasdiff for openapi.yaml (API-03), check-payload-compat.py — an additive-
# only JSON Schema comparison — for the payload schemas (API-41; this is the
# "JSON Schema compatibility check of each payload" API-41 names alongside
# `asyncapi diff`, which does not itself deeply diff payload schemas). A file
# that is new in this merge request has nothing to compare against and is
# skipped, not failed.
#
# Usage: run-oasdiff-gate.sh <base-sha>
set -u
base_sha="$1"
failed=0

for dir in */; do
  svc="${dir%/}"
  [ "$svc" = "spectral" ] && continue

  if [ -f "$svc/openapi.yaml" ] && git cat-file -e "$base_sha:$svc/openapi.yaml" 2>/dev/null; then
    git show "$base_sha:$svc/openapi.yaml" > /tmp/base-openapi.yaml
    echo "gst-ci: oasdiff breaking $svc/openapi.yaml against $base_sha"
    oasdiff breaking /tmp/base-openapi.yaml "$svc/openapi.yaml" --fail-on ERR || failed=1
  fi

  if [ -d "$svc/schemas" ]; then
    for schema in "$svc"/schemas/*.schema.json; do
      [ -f "$schema" ] || continue
      if git cat-file -e "$base_sha:$schema" 2>/dev/null; then
        git show "$base_sha:$schema" > /tmp/base-payload.schema.json
        echo "gst-ci: payload compatibility $schema against $base_sha"
        python3 spectral/check-payload-compat.py /tmp/base-payload.schema.json "$schema" || failed=1
      fi
    done
  fi
done

exit $failed
