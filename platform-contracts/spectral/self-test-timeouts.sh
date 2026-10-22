#!/usr/bin/env sh
# spectral/self-test-timeouts.sh — INV-T-3431 (INV-Q-3429 A, lesson P20);
# public operations INV-T-3491 (INV-REQ-1204 b7, INV-Q-3476, INV-Q-3510).
# Proves spectral/check-timeouts.py fires: a console-bff operation at 5000 fails;
# a fallback (any -> tenancy-svc) at 2000 fails; a gateway public /v1 operation
# at 5000 fails; requestIssuance at 3000 fails its own issuance row; a tree at
# the §36.6 values passes, with a multi-figure row (payload -> masterdata 80 ms /
# 1 s), console-bff->*, public operations checked (gateway -> ingestion-svc,
# the requestIssuance row, gateway -> any public /v1, console-web -> console-bff)
# and a figure-less row unchecked. Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")"
F=fixtures/timeout
failed=0
run() { python3 check-timeouts.py $F/sync-edges.yaml "$F/$1" 2>&1; }

out=$(run pass); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — §36.6-compliant fixture rejected:"; echo "$out"; failed=1; fi
case "$out" in *"9 operations match their §36.6 row, 1 unchecked."*) ;;
  *) echo "gst-ci: FAIL — pass fixture did not check 9 and list 1 unchecked:"; echo "$out"; failed=1;; esac

expect_fail() { # case, expected message fragment
  out=$(run "$1"); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail console-bff-5000 "(grantRole) x-timeout 5000: not a figure of row 'console-bff → any'"
expect_fail fallback-2000 "(getEntityStructure) x-timeout 2000: not a figure of row 'Any → tenancy-svc (fallback)'"
expect_fail public-5000 "(drainPeriod) x-timeout 5000: not a figure of row 'API gateway → any other service's public /v1 operation'"
expect_fail issuance-3000 "(requestIssuance) x-timeout 3000: not a figure of row 'API gateway → ingestion-svc requestIssuance'"

# INV-T-8456 (INV-AC-8455): the exclusion edge finds the row of the caller any; a sequence is
# checked against the row of every element and the error names the element.
for c in pass-exclusion-tenancy pass-exclusion-access pass-sequence; do
  out=$(run inv-ac-8455-$c); rc=$?
  if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — '$c' fixture rejected:"; echo "$out"; failed=1; fi
  case "$out" in *"1 operations match their §36.6 row, 0 unchecked."*) ;; *) echo "gst-ci: FAIL — '$c' did not check its one operation:"; echo "$out"; failed=1;; esac
done
expect_fail inv-ac-8455-exclusion-tenancy-2000 "(fixtureOp) x-timeout 2000: not a figure of row 'Any → tenancy-svc (fallback)'"
expect_fail inv-ac-8455-exclusion-access-2000 "(fixtureOp) x-timeout 2000: not a figure of row 'Any → access-svc (fallback)'"
expect_fail inv-ac-8455-sequence-different-timeouts "x-timeout 500: element 'rules-svc->tenancy-svc': not a figure of row 'rules-svc → tenancy-svc'"

[ "$failed" -eq 0 ] && echo "gst-ci: check-timeouts.py self-test passed."
exit $failed
