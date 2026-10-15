#!/usr/bin/env sh
# spectral/self-test-required-identifiers.sh — INV-T-4909 (lesson P20): proves
# check-required-identifiers.py fires. One passing fixture and one fixture for each failure:
# missing identifier, substring only, other case, missing file, unknown kind, missing column,
# empty table, no table at all, identifier present only in another service's directory.
set -u
cd "$(dirname "$0")"
F=fixtures/required-identifiers
failed=0
out=$(python3 check-required-identifiers.py $F/pass 2>&1) || { echo "gst-ci: FAIL — compliant fixture rejected:"; echo "$out"; failed=1; }
expect_fail() {
  out=$(python3 check-required-identifiers.py "$F/$1" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail missing-identifier "not found as a property"
expect_fail substring-only "not found as a property"
expect_fail other-case "not found as a property"
expect_fail missing-file "does not exist"
expect_fail unknown-kind "unknown kind"
expect_fail missing-column "four non-empty columns"
expect_fail empty-table "the table has no rows"
expect_fail no-table "no */required-identifiers.tsv found"
expect_fail other-service-only "not found as a property"
[ "$failed" -eq 0 ] && echo "gst-ci: check-required-identifiers.py self-test passed."
exit $failed
