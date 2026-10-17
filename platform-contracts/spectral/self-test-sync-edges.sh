#!/usr/bin/env sh
# spectral/self-test-sync-edges.sh — INV-T-2850 follow-up (API-08, lesson P20).
# Proves spectral/check-sync-edges.py fires: a compliant tree passes; a missing
# x-sync-edge, an edge not in the allowlist, a callee that is not the owning
# service each fail; console-bff->* passes only in service-runtime/ (INV-T-3007); the three caller forms (service, any, api-gateway) pass with the expected message. Needs
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

# INV-T-3007 (INV-Q-3425 A): console-bff->* is accepted only in service-runtime/.
out=$(run shared-pass); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — compliant shared-runtime fixture rejected:"; echo "$out"; failed=1; fi
expect_fail star-outside-runtime "x-sync-edge 'console-bff->*' is not an edge of sync-edges.yaml"
expect_fail star-other-caller "x-sync-edge 'canonical-svc->*' is not an edge of sync-edges.yaml"

# INV-T-6167 (INV-REQ-870, INV-AC-1922): only console-web may call console-bff; nothing may call the gateway.
F2=fixtures/sync-edge-callee
run_callee() { python3 check-sync-edges.py "$F2/$1" "$F2/root" 2>&1; }
for c in ok callee-lookalike; do
  out=$(run_callee $c.yaml); rc=$?
  if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — callee fixture '$c' rejected:"; echo "$out"; failed=1; fi
  case "$out" in *"allowlist check passed"*) ;; *) echo "gst-ci: FAIL — callee fixture '$c' did not print the passing line:"; echo "$out"; failed=1;; esac
done
expect_callee_fail() { # case, edge as written
  out=$(run_callee "$1.yaml"); rc=$?
  if [ $rc -ne 1 ]; then echo "gst-ci: FAIL — callee fixture '$1' exited $rc, expected 1"; failed=1
  else case "$out" in *"edge '$2' calls console-bff or the API gateway from a caller other than console-web"*) echo "gst-ci: self-test callee '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — callee fixture '$1' failed, but not for '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_callee_fail pipeline-caller "canonical-svc->console-bff"
expect_callee_fail any-caller "Any except access-svc->console-bff"
expect_callee_fail gateway-callee "console-web->API gateway"
expect_callee_fail gateway-callee-hyphen "canonical-svc->api-gateway"
expect_callee_fail bff-calls-bff "console-bff->console-bff"
expect_callee_fail gateway-calls-bff "API gateway->console-bff"
expect_callee_fail callee-case "canonical-svc->Console-BFF"
expect_callee_fail caller-lookalike "console-web-svc->console-bff"
expect_callee_fail caller-lookalike-2 "console-web2->console-bff"

[ "$failed" -eq 0 ] && echo "gst-ci: check-sync-edges.py self-test passed."
exit $failed
