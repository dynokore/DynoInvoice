#!/usr/bin/env sh
# spectral/self-test-entity-ref.sh — INV-T-8516, acceptance criterion INV-AC-8515 (API-24, API-25, lesson P20).
# Proves spectral/check-entity-ref.py fires: a compliant tree passes; one fixture per condition (a) to (f)
# fails with its own message fragment, with the look-alikes (key x-entity-refs, value 'entityRef ' and
# 'entityref', system-flow and sequence edges, console-bff->* outside service-runtime, a staged pair that is
# marked, a path with a trailing slash) and the (f) probes (external, http, missing, cyclic and allOf refs).
# Every case name carries INV-AC-8515. Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")"
F=fixtures/entity-ref
failed=0
run() { python3 check-entity-ref.py "$F/$1" 2>&1; }

expect_pass() { # case, expected output line
  out=$(run "$1"); rc=$?
  if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-8515 '$1' fixture rejected:"; echo "$out"; failed=1
  elif [ "$out" != "$2" ]; then echo "gst-ci: FAIL — INV-AC-8515 '$1' printed '$out', expected '$2'"; failed=1
  else echo "gst-ci: INV-AC-8515 self-test '$1' passed as expected ($2)"; fi
}
expect_fail() { # case, expected message fragment
  out=$(run "$1"); rc=$?
  if [ $rc -ne 1 ]; then echo "gst-ci: FAIL — INV-AC-8515 '$1' fixture exited $rc, expected 1"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: INV-AC-8515 self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — INV-AC-8515 '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}

# compliant trees: no-body and non-entityRef-body console-bff operations, a system-flow operation with a body
# entityRef, the service-runtime constant pair, allOf, and a non-JSON body without a schema all pass
expect_pass pass "entity-ref-check: 1 marked, 1 staged"
expect_pass pass-allof "entity-ref-check: 1 marked, 1 staged"
expect_pass pass-nonjson-without-schema "entity-ref-check: 1 marked, 0 staged"

# (a) value and body property
expect_fail a-value-lowercase "(a) request body has no top-level property 'entityref' with type string"
expect_fail a-value-trailing-space "(a) x-entity-ref value 'entityRef ' does not match"
expect_fail a-value-too-long "(a) x-entity-ref value"
expect_fail a-value-number "(a) x-entity-ref value 5 does not match"
expect_fail a-key-plural "(c) row of entity-ref-operations.tsv has no marked operation"
expect_fail a-property-integer "(a) request body has no top-level property 'entityRef' with type string"
expect_fail a-property-absent "(a) request body has no top-level property 'entityRef' with type string"
# (b) edge of the marked operation
expect_fail b-system-flow "(b) x-entity-ref needs x-sync-edge console-bff-><callee>, found 'canonical-svc->masterdata-svc'"
expect_fail b-sequence "(b) x-entity-ref needs x-sync-edge console-bff-><callee>, found ['console-bff->masterdata-svc'"
expect_fail b-absent "(b) x-entity-ref needs x-sync-edge console-bff-><callee>, found None"
expect_fail b-star-outside-runtime "(b) x-entity-ref needs x-sync-edge console-bff-><callee>, found 'console-bff->*'"
expect_fail b-edge-trailing-space "(b) x-entity-ref needs x-sync-edge"
# (c) marked operations against the data rows
expect_fail c-no-row "(c) marked operation has no row in entity-ref-operations.tsv"
expect_fail c-row-without-operation "(c) row of entity-ref-operations.tsv has no marked operation"
expect_fail c-path-trailing-slash "POST /internal/v1/masterdata/resolution/: (c) row of entity-ref-operations.tsv has no marked operation"
expect_fail c-method-lowercase "post /internal/v1/masterdata/resolution: (c) row of entity-ref-operations.tsv has no marked operation"
expect_fail c-row-property-differs "(c) marker value 'entityRef' differs from the row property 'entityref'"
expect_fail c-duplicate-row "entity-ref-operations.tsv: line 3 is a duplicate row"
# (d) candidates without a marker
expect_fail d-missing-marker "(d) console-bff operation with body entityRef has no x-entity-ref and is not staged"
expect_fail d-unstaged-candidate "(d) console-bff operation with body entityRef has no x-entity-ref and is not staged"
expect_fail d-constant-pair-other-directory "evidence: POST /internal/v1/evidence/hold: (d)"
expect_fail d-constant-lookalike-name "service-runtime: POST /internal/v1/jobs/run: (d)"
# (e) staged pairs
expect_fail e-staged-missing "evidence: noSuchOperation: (e) staged operation does not exist"
expect_fail e-staged-marked "masterdata: resolveGap: (e) staged operation is already marked"
expect_fail e-staged-not-candidate "masterdata: otherBody: (e) staged operation is not a candidate"
expect_fail e-staged-system-flow "masterdata: sysFlow: (e) staged operation is not a candidate"
# (f) bodies that cannot be fully resolved are never skipped
for c in f-external-ref f-external-ref-fragment f-http-ref f-external-in-allof f-external-in-oneof f-external-in-anyof f-marked-external-ref; do
  expect_fail $c "POST /internal/v1/evidence/hold: (f) request body cannot be fully resolved: \$ref"
done
expect_fail f-missing-component "(f) request body cannot be fully resolved: \$ref '#/components/schemas/Nope' has no target"
expect_fail f-request-body-ref-missing "(f) request body cannot be fully resolved: \$ref '#/components/requestBodies/Gone' has no target"
expect_fail f-cycle "(f) request body cannot be fully resolved: \$ref '#/components/schemas/A' forms a cycle"
expect_fail f-self-cycle-allof "(f) request body cannot be fully resolved: \$ref '#/components/schemas/A' forms a cycle"
expect_fail f-content-type-xml "(f) request body cannot be fully resolved: request body content type 'application/xml' is not application/json"
expect_fail f-content-type-charset "(f) request body cannot be fully resolved: request body content type 'application/json; charset=utf-8'"
# files
expect_fail yaml-unreadable "evidence/openapi.yaml: unreadable YAML"
expect_fail tsv-header "entity-ref-staged.tsv: the header line must be directory	operationId"
expect_fail tsv-fields "entity-ref-operations.tsv: line 2 has 3 fields, expected 4"
expect_fail tsv-staged-duplicate "entity-ref-staged.tsv: line 3 is a duplicate row"

# the repository itself passes (INV-AC-8515)
out=$(python3 check-entity-ref.py .. 2>&1); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-8515 repository tree rejected:"; echo "$out"; failed=1; fi

[ "$failed" -eq 0 ] && echo "gst-ci: INV-AC-8515 check-entity-ref.py self-test passed."
exit $failed
