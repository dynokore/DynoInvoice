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
# T6 (INV-T-2850) fixes two bugs this script's first real exercise (this is
# the first merge request where these openapi.yaml files already exist at the
# base commit — T5's own MR only ever saw them as new, "skipped, not failed")
# exposed:
#   1. The base copy went to /tmp, breaking every openapi.yaml's relative
#      `../service-runtime/schemas/*.schema.json` $ref (oasdiff resolves it
#      from /tmp, not from the copy's true directory) on every single
#      service — the base copy now sits alongside the real file instead, so
#      relative $refs resolve exactly as they do for the real file.
#   2. `service-runtime/schemas/*.schema.json` (the shared envelope and error
#      schemas, README: "service-runtime/ is not a service") is not a
#      per-event payload and is not versioned per event train the way
#      API-41 governs; it went through the exact same additive-only check as
#      a real service's event payload, which would permanently fail on this
#      directory's pre-existing `additionalProperties: false` (API-41 forbids
#      that on a payload; the shared envelope's field set is deliberately
#      closed, API-40) regardless of what changes. It is excluded from this
#      loop; `service-runtime/openapi.yaml`'s own oasdiff check is unaffected
#      and still runs.
#
# Usage: run-oasdiff-gate.sh <base-sha>
set -u
base_sha="$1"
failed=0

for dir in */; do
  svc="${dir%/}"
  [ "$svc" = "spectral" ] && continue

  if [ -f "$svc/openapi.yaml" ] && git cat-file -e "$base_sha:$svc/openapi.yaml" 2>/dev/null; then
    git show "$base_sha:$svc/openapi.yaml" > "$svc/.oasdiff-base-openapi.yaml"
    echo "gst-ci: oasdiff breaking $svc/openapi.yaml against $base_sha"
    oasdiff breaking "$svc/.oasdiff-base-openapi.yaml" "$svc/openapi.yaml" --fail-on ERR || failed=1
    rm -f "$svc/.oasdiff-base-openapi.yaml"
  fi

  [ "$svc" = "service-runtime" ] && continue   # the shared envelope, not a per-event payload (see above)

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
