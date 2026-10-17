#!/usr/bin/env sh
# spectral/self-test-enum-casing.sh — INV-T-6047 (owner ruling INV-Q-6109).
# Proves inv-api-09-enum-casing and its x-enum-varnames siblings: an enum without
# x-enum-varnames has UPPER_SNAKE_CASE members; an enum with a sibling x-enum-varnames has
# UPPER_SNAKE_CASE varnames, camelCase values and equal counts. Called from the end of
# self-test-money-rule.sh (job money-rule-self-test). Run from the SPECTRAL_IMAGE.
set -u
cd "$(dirname "$0")"
failed=0

expect_pass() {
  if ! spectral lint --ruleset ruleset.yaml --fail-severity error "fixtures/enum-casing-$1.yaml" >/tmp/enum-casing.out 2>&1; then
    echo "gst-ci: FAIL — enum-casing fixture $1 must PASS but was rejected"
    cat /tmp/enum-casing.out
    failed=1
  else
    echo "gst-ci: enum-casing $1: pass (expected)"
  fi
}

expect_fail() {
  if spectral lint --ruleset ruleset.yaml --fail-severity error "fixtures/enum-casing-$1.yaml" >/tmp/enum-casing.out 2>&1; then
    echo "gst-ci: FAIL — enum-casing fixture $1 must FAIL but was accepted"
    cat /tmp/enum-casing.out
    failed=1
  elif ! grep -q 'inv-api-09-enum-casing' /tmp/enum-casing.out; then
    echo "gst-ci: FAIL — enum-casing fixture $1 failed, but not on inv-api-09-enum-casing:"
    cat /tmp/enum-casing.out
    failed=1
  else
    echo "gst-ci: enum-casing $1: fail on inv-api-09-enum-casing (expected)"
  fi
}

expect_pass 1-ok-varnames
expect_pass 2-ok-plain
for fx in 3-snake-no-varnames 4-camel-no-varnames 5-varname-snake 6-value-snake 7-count 8a-singular 8b-parent; do
  expect_fail "$fx"
done

if [ "$failed" -eq 0 ]; then
  echo "gst-ci: inv-api-09-enum-casing self-test passed."
fi
exit $failed
