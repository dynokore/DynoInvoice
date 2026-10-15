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
[ "$failed" -eq 0 ] && echo "gst-ci: check-forbidden-properties.py self-test passed."
exit $failed
