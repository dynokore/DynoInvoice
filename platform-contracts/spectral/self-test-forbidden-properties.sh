#!/usr/bin/env sh
# spectral/self-test-forbidden-properties.sh — INV-T-4910 (lesson P20): proves
# check-forbidden-properties.py fires. Fails: a response with passcode, PassCode, pass_code; an event
# payload with passcode; a missing table; an empty table. Passes: passcodeAttempts, wrongEntryCount,
# the request that carries passcode, the MessageRequested payload with sealedPasscode.
set -u
cd "$(dirname "$0")"
F=fixtures/forbidden-properties
failed=0
for c in pass-attempts pass-request-passcode pass-wrong-entry-count pass-sealed-passcode; do
  out=$(python3 check-forbidden-properties.py "$F/$c" 2>&1) || { echo "gst-ci: FAIL — '$c' rejected:"; echo "$out"; failed=1; }
done
expect_fail() {
  out=$(python3 check-forbidden-properties.py "$F/$1" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail fail-response-passcode "is forbidden in a response or event schema"
expect_fail fail-response-mixed-case "is forbidden in a response or event schema"
expect_fail fail-response-underscore "is forbidden in a response or event schema"
expect_fail fail-event-passcode "x-payload.schema.json"
expect_fail fail-no-table "forbidden-properties.tsv is missing"
expect_fail fail-empty-table "the table has no rows"

# --requests (INV-T-6499): the request-side check. pass-credit-note-kind holds a copy of the real table.
R=fixtures/forbidden-request-properties
for c in pass-reserved-optional pass-response-only pass-lookalike pass-enum-reserved pass-credit-note-kind; do
  out=$(python3 check-forbidden-properties.py --requests "$R/$c" 2>&1) || { echo "gst-ci: FAIL — '$c' rejected:"; echo "$out"; failed=1; }
done
expect_req_fail() {
  out=$(python3 check-forbidden-properties.py --requests "$R/$1" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
for c in fail-request-category-code fail-request-mixed-case fail-request-underscore fail-reserved-required \
         fail-reserved-no-prefix fail-reserved-other-schema fail-enum-member fail-shared-schema \
         fail-inline-request fail-no-operation-id fail-parameter fail-allof fail-external-ref; do
  expect_req_fail "$c" "is forbidden in a request schema"
done
expect_req_fail fail-reserved-required "(reserved item must be optional and start with Reserved:)"
expect_req_fail fail-reserved-no-prefix "(reserved item must be optional and start with Reserved:)"
expect_req_fail fail-no-table "forbidden-request-properties.tsv is missing"
expect_req_fail fail-empty-table "the table has no rows"
expect_req_fail fail-bad-kind "bad row"
# --table / --discover (INV-T-7286, INV-REQ-1030, INV-Q-7280 A): the shared score table over discovered directories.
S=fixtures/forbidden-score-properties
T=forbidden-score-properties.tsv
for c in pass-clean pass-request-only; do
  out=$(python3 check-forbidden-properties.py --table "$T" --discover "$S/$c" 2>&1) || { echo "gst-ci: FAIL — '$c' rejected:"; echo "$out"; failed=1; }
done
expect_score_fail() { # case expected-message args...
  c=$1; msg=$2; shift 2
  out=$(python3 check-forbidden-properties.py "$@" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$c' fixture was accepted"; failed=1
  else case "$out" in *"$msg"*) echo "gst-ci: self-test '$c' failed as expected ($msg)";;
       *) echo "gst-ci: FAIL — '$c' failed, but not with '$msg':"; echo "$out"; failed=1;; esac; fi
}
expect_score_fail fail-response-camel "property 'complianceScore' is forbidden" --table "$T" --discover "$S/fail-response-camel"
expect_score_fail fail-response-snake "property 'compliance_score' is forbidden" --table "$T" --discover "$S/fail-response-snake"
expect_score_fail fail-response-pascal "property 'ComplianceScore' is forbidden" --table "$T" --discover "$S/fail-response-pascal"
expect_score_fail fail-response-bare "property 'score' is forbidden" --table "$T" --discover "$S/fail-response-bare"
expect_score_fail fail-event-schema "x.schema.json: x.schema.json: property 'healthPercent' is forbidden" --table "$T" --discover "$S/fail-event-schema"
expect_score_fail fail-discovered-unlisted "svc-b/asyncapi.yaml: asyncapi.yaml: property 'healthIndex' is forbidden" --table "$T" --discover "$S/fail-discovered-unlisted"
expect_score_fail fail-no-table "the table is missing" --table "$S/fail-no-table/table.tsv" --discover "$S/fail-no-table"
expect_score_fail fail-empty-table "the table has no rows" --table "$S/fail-empty-table/table.tsv" --discover "$S/fail-empty-table"
expect_score_fail fail-no-contract-dir "no contract directory found under" --table "$T" --discover "$S/fail-no-contract-dir"
expect_score_fail fail-discover-without-table "--discover needs --table" --discover "$S/fail-discover-without-table"
expect_score_fail fail-table-with-requests "--table cannot be combined with --requests" --table "$T" --requests "$S/fail-table-with-requests"
[ "$failed" -eq 0 ] && echo "gst-ci: check-forbidden-properties.py self-test passed."
exit $failed
