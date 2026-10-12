#!/usr/bin/env sh
# spectral/self-test-workload-rule.sh — INV-T-4030 (API-23/API-25 workload note,
# owner rulings INV-Q-3826 A and INV-Q-4006 A; lesson P20).
# Proves the Spectral rule inv-api-02-x-principal-workload-restricted accepts
# x-principal `workload` on an installer-only operation (installer-><callee>) and
# on an api-gateway->access-svc operation, and refuses it on any other edge, on a
# gateway edge to another callee, and without x-sync-edge.
# Accepted fixtures carry a refused control operation, so a missing or renamed
# rule fails the self-test instead of passing it.
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
# Each accepted fixture also holds one refused control operation
# (/internal/v1/fixture/control): the rule must fire exactly once, on the
# control operation, and never on the accepted one (/internal/v1/fixture/op).
for fx in workload-installer-ok workload-gateway-access-ok; do
  out=$(lint fixtures/$fx.yaml)
  hits=$(printf '%s\n' "$out" | grep -c "$rule")
  on_control=$(printf '%s\n' "$out" | grep "$rule" | grep -c "fixture/control")
  if [ "$hits" -eq 1 ] && [ "$on_control" -eq 1 ]; then
    echo "gst-ci: self-test $fx accepted by $rule as expected (control operation refused, so the rule ran)"
  else
    echo "gst-ci: FAIL — $fx: expected $rule exactly once, on the control operation only (hits=$hits, on control=$on_control):"; echo "$out"; failed=1
  fi
done
[ "$failed" -eq 0 ] && echo "gst-ci: $rule self-test passed."
exit $failed
