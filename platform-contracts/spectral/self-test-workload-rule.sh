#!/usr/bin/env sh
# spectral/self-test-workload-rule.sh — INV-T-4030 (API-23/API-25 workload note,
# owner rulings INV-Q-3826 A and INV-Q-4006 A; lesson P20).
# Proves the Spectral rule inv-api-02-x-principal-workload-restricted accepts
# x-principal `workload` on an installer-only operation (installer-><callee>) and
# on an api-gateway->access-svc operation, and refuses it on any other edge, on a
# gateway edge to another callee, and without x-sync-edge.
# Accepted fixtures carry a refused control operation, so a missing or renamed
# rule fails the self-test instead of passing it.
# INV-T-4075 (API-02): also proves inv-api-02-x-principal refuses an operation
# without x-principal or with a value outside the API-23 scopes and `workload`,
# and accepts an API-23 scope (with a refused control operation, as above).
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
# INV-T-4075: inv-api-02-x-principal. Its name is a prefix of the workload rule's,
# so a hit is matched only when the name is followed by whitespace or line end.
prule=inv-api-02-x-principal
phit() { printf '%s\n' "$1" | grep -E "(^|[[:space:]])$prule([[:space:]]|\$)"; }
for fx in x-principal-missing x-principal-outside-scopes; do
  out=$(lint fixtures/$fx.yaml)
  if [ -n "$(phit "$out")" ]; then echo "gst-ci: self-test $fx refused by $prule as expected"
  else echo "gst-ci: FAIL — $fx did not trigger $prule:"; echo "$out"; failed=1; fi
done
for fx in x-principal-scope-ok; do
  out=$(lint fixtures/$fx.yaml)
  hits=$(phit "$out" | grep -c .)
  on_control=$(phit "$out" | grep -c "fixture/control")
  if [ "$hits" -eq 1 ] && [ "$on_control" -eq 1 ]; then
    echo "gst-ci: self-test $fx accepted by $prule as expected (control operation refused, so the rule ran)"
  else
    echo "gst-ci: FAIL — $fx: expected $prule exactly once, on the control operation only (hits=$hits, on control=$on_control):"; echo "$out"; failed=1
  fi
done
[ "$failed" -eq 0 ] && echo "gst-ci: $rule and $prule self-tests passed."
exit $failed
