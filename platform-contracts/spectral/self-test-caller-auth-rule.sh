#!/usr/bin/env sh
# spectral/self-test-caller-auth-rule.sh — INV-T-8269 (INV-AC-8259, INV-REQ-1202,
# owner answer INV-Q-8201 A).
# Proves the Spectral rule inv-req-1202-x-caller-auth refuses an operation without
# x-caller-auth and with the look-alike values End-User, signed_link, service-jobs,
# "end-user " (trailing space), "" (empty string), [end-user] (a list) and workload,
# one rule hit each, on the operation named; and accepts end-user, signed-link and
# service-job. Accepted fixtures carry a refused control operation
# (/internal/v1/fixture/control), so a missing or renamed rule fails the self-test
# instead of passing it.
# Run from an image with the `spectral` binary and node ($SPECTRAL_IMAGE).
set -u
cd "$(dirname "$0")"
failed=0
rule=inv-req-1202-x-caller-auth
# hits_of <file>: sets HITS to the paths of the hits of $rule, one per line.
hits_of() {
  out=$(spectral lint --ruleset ruleset.yaml --fail-severity error -f json "$1" 2>/dev/null)
  HITS=$(printf '%s' "$out" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const h=[];for(const r of JSON.parse(s))if(r.code===process.argv[1])h.push(r.path.join("."));console.log(h.sort().join("\n"))})' "$rule")
}
# INV-AC-8259 refused: exactly one hit, on the operation /v1/fixture/op.
for fx in missing uppercase underscore plural trailing-space empty list workload; do
  hits_of fixtures/x-caller-auth-$fx.yaml
  hits=$(printf '%s\n' "$HITS" | grep -c .)
  if [ "$hits" -eq 1 ] && printf '%s\n' "$HITS" | grep -qE '^paths\./v1/fixture/op\.post(\.x-caller-auth)?$'; then
    echo "gst-ci: INV-AC-8259 self-test x-caller-auth-$fx refused by $rule as expected"
  else
    echo "gst-ci: FAIL — INV-AC-8259 x-caller-auth-$fx: expected $rule exactly once, on /v1/fixture/op (hits=$hits):"; echo "$HITS"; failed=1
  fi
done
# INV-AC-8259 accepted: the rule fires exactly once, on the control operation only.
for fx in ok-end-user ok-signed-link ok-service-job; do
  hits_of fixtures/x-caller-auth-$fx.yaml
  hits=$(printf '%s\n' "$HITS" | grep -c .)
  on_control=$(printf '%s\n' "$HITS" | grep -c '^paths\./internal/v1/fixture/control\.get$')
  if [ "$hits" -eq 1 ] && [ "$on_control" -eq 1 ]; then
    echo "gst-ci: INV-AC-8259 self-test x-caller-auth-$fx accepted by $rule as expected (control operation refused, so the rule ran)"
  else
    echo "gst-ci: FAIL — INV-AC-8259 x-caller-auth-$fx: expected $rule exactly once, on the control operation only (hits=$hits, on control=$on_control):"; echo "$HITS"; failed=1
  fi
done
[ "$failed" -eq 0 ] && echo "gst-ci: $rule self-test passed."
exit $failed
