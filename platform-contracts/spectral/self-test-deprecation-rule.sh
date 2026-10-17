#!/usr/bin/env sh
# spectral/self-test-deprecation-rule.sh — INV-T-6165 (API-31, INV-REQ-824).
# Proves the Spectral rule inv-api-31-deprecation-headers refuses a response of a
# deprecated operation that lacks any of the Deprecation, Sunset and Link headers
# (names compared exactly), and accepts a deprecated operation whose responses all
# declare them. The accepted fixture carries a refused control operation, so a
# missing or renamed rule fails the self-test instead of passing it.
# Also proves inv-api-31-deprecation-sunset stays independent of the new rule.
# Run from an image with the `spectral` binary ($SPECTRAL_IMAGE).
set -u
cd "$(dirname "$0")"
failed=0
rule=inv-api-31-deprecation-headers
srule=inv-api-31-deprecation-sunset
lint() { spectral lint --ruleset ruleset.yaml --fail-severity error "$1" 2>&1; }
# A rule name is matched as a whole word: preceded by line start or whitespace,
# followed by whitespace or line end.
hit() { printf '%s\n' "$2" | grep -E "(^|[[:space:]])$1([[:space:]]|\$)"; }
d=fixtures/deprecation
for fx in missing-deprecation missing-sunset missing-link lookalike-header lowercase-header no-headers-block; do
  out=$(lint $d/$fx.yaml)
  if [ -n "$(hit $rule "$out")" ]; then echo "gst-ci: self-test $fx refused by $rule as expected"
  else echo "gst-ci: FAIL — $fx did not trigger $rule:"; echo "$out"; failed=1; fi
done
# The 200 declares all three headers; only the 404 is bare, so the report path names 404.
out=$(lint $d/error-response-bare.yaml)
hits=$(hit $rule "$out" | grep -c .)
on_404=$(hit $rule "$out" | grep -c "404")
if [ "$hits" -eq 1 ] && [ "$on_404" -eq 1 ]; then
  echo "gst-ci: self-test error-response-bare refused by $rule on the 404 response as expected"
else
  echo "gst-ci: FAIL — error-response-bare: expected $rule exactly once, on 404 (hits=$hits, on 404=$on_404):"; echo "$out"; failed=1
fi
# Accepted fixture: the rule fires exactly once, on the control operation only.
out=$(lint $d/headers-ok.yaml)
hits=$(hit $rule "$out" | grep -c .)
on_control=$(hit $rule "$out" | grep -c "fixture/control")
if [ "$hits" -eq 1 ] && [ "$on_control" -eq 1 ]; then
  echo "gst-ci: self-test headers-ok accepted by $rule as expected (control operation refused, so the rule ran)"
else
  echo "gst-ci: FAIL — headers-ok: expected $rule exactly once, on the control operation only (hits=$hits, on control=$on_control):"; echo "$out"; failed=1
fi
# No x-sunset but all headers: only the sunset rule reports.
out=$(lint $d/no-x-sunset.yaml)
if [ -n "$(hit $srule "$out" | grep "fixture/old")" ] && [ -z "$(hit $rule "$out")" ]; then
  echo "gst-ci: self-test no-x-sunset refused by $srule and not by $rule as expected"
else
  echo "gst-ci: FAIL — no-x-sunset: expected $srule only:"; echo "$out"; failed=1
fi
# Look-alike properties (path-level parameters, x-get, deprecated: false): the
# anchored operation filter selects none of them, so neither rule reports.
out=$(lint $d/lookalike-property.yaml)
if [ -z "$(hit $rule "$out")" ] && [ -z "$(hit $srule "$out")" ]; then
  echo "gst-ci: self-test lookalike-property reported by neither $rule nor $srule as expected"
else
  echo "gst-ci: FAIL — lookalike-property: expected no hit of $rule or $srule:"; echo "$out"; failed=1
fi
[ "$failed" -eq 0 ] && echo "$rule self-test passed"
exit $failed
