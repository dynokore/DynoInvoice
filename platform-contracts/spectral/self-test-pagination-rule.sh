#!/usr/bin/env sh
# spectral/self-test-pagination-rule.sh — INV-T-6180 (API-18, INV-REQ-826, INV-AC-1876;
# owner ruling INV-Q-6163 A).
# Proves the Spectral rule inv-api-18-cursor-pagination refuses a list operation
# whose 200 is a bare array, that lacks limit (integer, default 100, maximum 500) or
# cursor (string), that uses look-alike parameter names, whose items is not a
# required array, or whose nextCursor is required or not a string; and accepts a
# paged list operation, a single-resource GET and a GET with a differently named
# array (a documented limit of the rule). The accepted fixture carries a refused
# control list operation, so a missing or renamed rule fails the self-test instead
# of passing it.
# Run from an image with the `spectral` binary ($SPECTRAL_IMAGE).
set -u
cd "$(dirname "$0")"
failed=0
rule=inv-api-18-cursor-pagination
lint() { spectral lint --ruleset ruleset.yaml --fail-severity error "$1" 2>&1; }
# A rule name is matched as a whole word: preceded by line start or whitespace,
# followed by whitespace or line end.
hit() { printf '%s\n' "$2" | grep -E "(^|[[:space:]])$1([[:space:]]|\$)"; }
d=fixtures/pagination
# Each refused fixture holds one list operation and the rule must report it exactly once.
for fx in bare-array missing-limit missing-cursor limit-default-101 limit-maximum-501 limit-string \
  cursor-integer lookalike-params path-param-no-paging without-items-required \
  next-cursor-required next-cursor-integer; do
  out=$(lint $d/$fx.yaml)
  hits=$(hit $rule "$out" | grep -c .)
  on_list=$(hit $rule "$out" | grep -c "fixture/things")
  if [ "$hits" -eq 1 ] && [ "$on_list" -eq 1 ]; then echo "gst-ci: self-test $fx refused by $rule as expected"
  else echo "gst-ci: FAIL — $fx: expected $rule exactly once, on the list operation (hits=$hits, on list=$on_list):"; echo "$out"; failed=1; fi
done
# items-not-array: by the task's own definition an object whose `items` is not an array is
# not a list operation, so condition (2) `items` is an array can never fail on a selected
# operation (GAP reported in the MR). Pinned here as "not reported" until the owner rules.
out=$(lint $d/items-not-array.yaml)
if [ -z "$(hit $rule "$out")" ]; then echo "gst-ci: self-test items-not-array not selected as a list operation, as the rule text defines"
else echo "gst-ci: FAIL — items-not-array unexpectedly reported by $rule:"; echo "$out"; failed=1; fi
# The accepted document also holds one refused control list operation (fixture/control):
# the rule must fire exactly once, on the control operation only.
out=$(lint $d/paged-ok.yaml)
hits=$(hit $rule "$out" | grep -c .)
on_control=$(hit $rule "$out" | grep -c "fixture/control")
if [ "$hits" -eq 1 ] && [ "$on_control" -eq 1 ]; then
  echo "gst-ci: self-test paged-ok accepted by $rule as expected (control operation refused, so the rule ran)"
else
  echo "gst-ci: FAIL — paged-ok: expected $rule exactly once, on the control operation only (hits=$hits, on control=$on_control):"; echo "$out"; failed=1
fi
[ "$failed" -eq 0 ] && echo "gst-ci: $rule self-test passed"
exit $failed
