#!/usr/bin/env sh
# spectral/self-test-schema-samples.sh — INV-T-2850 follow-up (lesson P20).
# Proves validate-schema-samples.py --discover fires: a compliant tree passes;
# an examples/valid sample that violates its schema, an examples/invalid sample
# that satisfies it, and a samples directory with no declared schema each fail.
set -u
cd "$(dirname "$0")"
N=fixtures/schema-samples-negative
failed=0
out=$(python3 validate-schema-samples.py --discover $N/pass 2>&1) || { echo "gst-ci: FAIL — compliant fixture rejected:"; echo "$out"; failed=1; }
expect_fail() {
  out=$(python3 validate-schema-samples.py --discover "$N/$1" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail valid-sample-fails "expected VALID but failed"
expect_fail invalid-sample-passes "expected INVALID but passed"
expect_fail no-schema-declared "no schema.txt"
[ "$failed" -eq 0 ] && echo "gst-ci: validate-schema-samples.py self-test passed."
exit $failed
