#!/usr/bin/env sh
# spectral/self-test-sync-edges.sh — INV-T-2850 follow-up (API-08, lesson P20).
# Proves spectral/check-sync-edges.py fires: a compliant tree passes; a missing
# x-sync-edge, an edge not in the allowlist, a callee that is not the owning
# service each fail; the shared-runtime rule (INV-T-3007) passes and fails as expected; the three caller forms (service, any, api-gateway) pass with the expected message. Needs
# python3 + PyYAML.
set -u
cd "$(dirname "$0")"
F=fixtures/sync-edge
failed=0
run() { python3 check-sync-edges.py $F/allowlist.yaml "$F/$1" 2>&1; }

out=$(run pass); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — compliant fixture rejected:"; echo "$out"; failed=1; fi
# the pass fixture holds one edge of each form: console-bff->, any-> ("Any except X") and api-gateway-> ("API gateway")
case "$out" in *"3 x-sync-edge values"*) ;; *) echo "gst-ci: FAIL — pass fixture did not check its three edge forms:"; echo "$out"; failed=1;; esac

expect_fail() { # case, expected message fragment
  out=$(run "$1"); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail missing "internal operation has no x-sync-edge"
expect_fail not-in-allowlist "is not an edge of sync-edges.yaml"
expect_fail wrong-callee "names a callee other than the owning service"

# INV-T-3007: the shared runtime contract (service-runtime/openapi.yaml) names the
# placeholder callee owning-svc; its caller needs an edge to every service that
# declares x-scheduled-jobs.
out=$(run shared-pass); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — compliant shared-runtime fixture rejected:"; echo "$out"; failed=1; fi
expect_fail shared-no-edge "masterdata-svc declares x-scheduled-jobs but sync-edges.yaml has no edge console-bff->masterdata-svc"
expect_fail shared-wrong-callee "must name the callee 'owning-svc' in the shared runtime contract"

[ "$failed" -eq 0 ] && echo "gst-ci: check-sync-edges.py self-test passed."
exit $failed
