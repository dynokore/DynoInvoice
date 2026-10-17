#!/usr/bin/env sh
# spectral/self-test-money-rule.sh — INV-T-2855 (T11), coordinator-authorised
# scope extension.
#
# Proves inv-api-12-money-format itself works against an AsyncAPI document
# and a standalone event JSON Schema document, not only an OpenAPI one — the
# same way self-test-message-envelope-rule.sh proves inv-api-40's rule works,
# against isolated fixtures. T10 slipped a JSON-`number`-typed money field
# past CI this way (fixed by hand in 60d98fe, review-only — CI itself never
# caught it, because inv-api-12-money-format's `formats:` was `["oas3_1"]`
# only). Run from an image with the `spectral` binary ($SPECTRAL_IMAGE in
# .gitlab-ci.yml).
set -u
cd "$(dirname "$0")"
failed=0

echo "gst-ci: self-test — an AsyncAPI message with a decimal-string money field must PASS"
if ! spectral lint --ruleset ruleset.yaml --fail-severity error fixtures/asyncapi-money-as-string.yaml >/tmp/asyncapi-money-ok.out 2>&1; then
  echo "gst-ci: FAIL — a compliant AsyncAPI money field was rejected"
  cat /tmp/asyncapi-money-ok.out
  failed=1
fi

echo "gst-ci: self-test — an AsyncAPI message with a JSON-number money field must FAIL on inv-api-12-money-format"
if spectral lint --ruleset ruleset.yaml --fail-severity error fixtures/asyncapi-money-as-number.yaml >/tmp/asyncapi-money-bad.out 2>&1; then
  echo "gst-ci: FAIL — a non-compliant AsyncAPI money field was accepted"
  cat /tmp/asyncapi-money-bad.out
  failed=1
elif ! grep -q 'inv-api-12-money-format' /tmp/asyncapi-money-bad.out; then
  echo "gst-ci: FAIL — the fixture failed lint, but not on inv-api-12-money-format:"
  cat /tmp/asyncapi-money-bad.out
  failed=1
fi

echo "gst-ci: self-test — a standalone event JSON Schema with a decimal-string money field must PASS"
if ! spectral lint --ruleset ruleset.yaml --fail-severity error fixtures/money-payload-as-string.schema.json >/tmp/schema-money-ok.out 2>&1; then
  echo "gst-ci: FAIL — a compliant standalone schema money field was rejected"
  cat /tmp/schema-money-ok.out
  failed=1
fi

echo "gst-ci: self-test — a standalone event JSON Schema with a JSON-number money field must FAIL on inv-api-12-money-format"
if spectral lint --ruleset ruleset.yaml --fail-severity error fixtures/money-payload-as-number.schema.json >/tmp/schema-money-bad.out 2>&1; then
  echo "gst-ci: FAIL — a non-compliant standalone schema money field was accepted"
  cat /tmp/schema-money-bad.out
  failed=1
elif ! grep -q 'inv-api-12-money-format' /tmp/schema-money-bad.out; then
  echo "gst-ci: FAIL — the fixture failed lint, but not on inv-api-12-money-format:"
  cat /tmp/schema-money-bad.out
  failed=1
fi

if [ "$failed" -eq 0 ]; then
  echo "gst-ci: inv-api-12-money-format self-test passed (fails a number-typed money field in AsyncAPI and a standalone event schema, passes a decimal-string one)."
fi
exit $failed
