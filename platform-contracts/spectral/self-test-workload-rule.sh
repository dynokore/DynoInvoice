#!/usr/bin/env sh
# spectral/self-test-workload-rule.sh — INV-T-4030 (API-23/API-25 workload note,
# owner rulings INV-Q-3826 A and INV-Q-4006 A; lesson P20).
# Proves the Spectral rule inv-api-02-x-principal-workload-restricted accepts
# x-principal `workload` on an installer-only operation (installer-><callee>) and
# on an api-gateway->access-svc operation, and refuses it on any other edge, on a
# gateway edge to another callee, and without x-sync-edge.
# Run from an image with the `spectral` binary ($SPECTRAL_IMAGE).
set -u
cd "$(dirname "$0")"
failed=0
rule=inv-api-02-x-principal-workload-restricted
lint() { spectral lint --ruleset ruleset.yaml --fail-severity error "$1" 2>&1; }
for fx in workload-other-edge workload-gateway-other-callee workload-no-edge; do
  out=$(lint fixtures/$fx.yaml)
  case "$out" in *$rule*) echo "gst-ci: self-test $fx refused by $rule as expected";;
    *) echo "gst-ci: FAIL — $fx did not trigger $rule:"; echo "$out"; failed=1;; esac
done
for fx in workload-installer-ok workload-gateway-access-ok; do
  out=$(lint fixtures/$fx.yaml)
  case "$out" in *$rule*) echo "gst-ci: FAIL — $fx wrongly triggered $rule:"; echo "$out"; failed=1;;
    *) echo "gst-ci: self-test $fx accepted by $rule as expected";; esac
done
[ "$failed" -eq 0 ] && echo "gst-ci: $rule self-test passed."
exit $failed
