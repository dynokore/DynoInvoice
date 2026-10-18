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
[ "$failed" -eq 0 ] && echo "gst-ci: check-forbidden-properties.py self-test passed."
exit $failed
