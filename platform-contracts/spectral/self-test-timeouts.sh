#!/usr/bin/env sh
# spectral/self-test-timeouts.sh — INV-T-3431 (INV-Q-3429 A, lesson P20).
# Proves spectral/check-timeouts.py fires: a console-bff operation at 5000 fails;
# a fallback (any -> tenancy-svc) at 2000 fails; a tree at the §36.6 values passes,
# with a multi-figure row (payload -> masterdata 80 ms / 1 s), console-bff->*,
# a figure-less row and a public operation unchecked, and an INV-T-3491 operation
# deferred. Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")"
F=fixtures/timeout
failed=0
run() { python3 check-timeouts.py $F/sync-edges.yaml "$F/$1" 2>&1; }

out=$(run pass); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — §36.6-compliant fixture rejected:"; echo "$out"; failed=1; fi
case "$out" in *"4 operations match their §36.6 row, 2 unchecked, 1 deferred"*) ;;
  *) echo "gst-ci: FAIL — pass fixture did not check 4, list 2 unchecked and defer 1:"; echo "$out"; failed=1;; esac

expect_fail() { # case, expected message fragment
  out=$(run "$1"); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail console-bff-5000 "(grantRole) x-timeout 5000: not a figure of row 'console-bff → any'"
expect_fail fallback-2000 "(getEntityStructure) x-timeout 2000: not a figure of row 'Any → tenancy-svc (fallback)'"

[ "$failed" -eq 0 ] && echo "gst-ci: check-timeouts.py self-test passed."
exit $failed
