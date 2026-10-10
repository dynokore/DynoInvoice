#!/usr/bin/env sh
# spectral/self-test-sync-edges.sh — INV-T-2850 follow-up (API-08, lesson P20).
# Proves spectral/check-sync-edges.py fires: a compliant tree passes; a missing
# x-sync-edge, an edge not in the allowlist, a callee that is not the owning
# service and a stale exemption each fail with the expected message. Needs
# python3 + PyYAML.
set -u
cd "$(dirname "$0")"
F=fixtures/sync-edge
failed=0
run() { python3 check-sync-edges.py $F/allowlist.yaml $F/ruleset.yaml "$F/$1" 2>&1; }

out=$(run pass); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — compliant fixture rejected:"; echo "$out"; failed=1; fi
case "$out" in *"1 exempt gaps"*) ;; *) echo "gst-ci: FAIL — pass fixture did not report its exempt gap:"; echo "$out"; failed=1;; esac

expect_fail() { # case, expected message fragment
  out=$(run "$1"); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail missing "internal operation has no x-sync-edge"
expect_fail not-in-allowlist "is not an edge of sync-edges.yaml"
expect_fail wrong-callee "names a callee other than the owning service"
expect_fail stale-exemption "stale API-08 exemption"

[ "$failed" -eq 0 ] && echo "gst-ci: check-sync-edges.py self-test passed."
exit $failed
