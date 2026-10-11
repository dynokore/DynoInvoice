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
# INV-T-2850 follow-up (T6 owns this gate): the first real contract merge
# request for a directory deletes T5's placeholder operation
# (`/v1/<svc>/examples`, operationId `createExample`) rather than
# deprecating it — deprecation is not viable, since generated clients treat
# any deprecation warning as an error (`allWarningsAsErrors`), and it is not
# a real operation any of them actually calls. Before the diff,
# strip-scaffold-placeholder.py deletes that one exact operation from the
# BASE copy IF AND ONLY IF the base copy still carries it byte-for-byte
# (path, sole method, operationId and T5's own "INV-T-2849 scaffold" marker
# all match) — see that script for the full rule. So its removal is never
# seen as a removal at all, while removing anything else, in any directory,
# still fails (CS-50).
#
# INV-T-3491 gate fix (INV-Q-3569, CS-50): the pinned oasdiff reads only its own
# list form of an extensible enum (`x-extensible-enum: [..]`), so a value added to
# an API-09 response enum (`enum` + `x-extensible-enum: true`), which API-09 calls
# additive, was reported as breaking. normalize-extensible-enum.py rewrites BOTH
# copies to the list form before the diff; unmarked enums are untouched and still
# fail. Ruled one-off exceptions sit in oasdiff-exceptions.txt (--err-ignore), each
# matching one operation and one oasdiff message and citing its Keel ruling.
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
    python3 spectral/strip-scaffold-placeholder.py "$svc" "$svc/.oasdiff-base-openapi.yaml"
    echo "gst-ci: oasdiff breaking $svc/openapi.yaml against $base_sha"
    python3 spectral/normalize-extensible-enum.py "$svc/.oasdiff-base-openapi.yaml" "$svc/.oasdiff-base-openapi.yaml"
    python3 spectral/normalize-extensible-enum.py "$svc/openapi.yaml" "$svc/.oasdiff-head-openapi.yaml"
    oasdiff breaking "$svc/.oasdiff-base-openapi.yaml" "$svc/.oasdiff-head-openapi.yaml" --fail-on ERR \
      --err-ignore spectral/oasdiff-exceptions.txt || failed=1
    rm -f "$svc/.oasdiff-base-openapi.yaml" "$svc/.oasdiff-head-openapi.yaml"
  fi

  if [ -d "$svc/schemas" ]; then
    for schema in "$svc"/schemas/*.schema.json; do
      [ -f "$schema" ] || continue
      case "$(basename "$schema")" in
        cloud-event-envelope.schema.json|error-envelope.schema.json) continue ;;
      esac
      if git cat-file -e "$base_sha:$schema" 2>/dev/null; then
        git show "$base_sha:$schema" > /tmp/base-payload.schema.json
        echo "gst-ci: payload compatibility $schema against $base_sha"
        python3 spectral/check-payload-compat.py /tmp/base-payload.schema.json "$schema" || failed=1
      fi
    done
  fi
done

exit $failed
