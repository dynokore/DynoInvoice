#!/usr/bin/env sh
# spectral/self-test-message-envelope-rule.sh — INV-T-2850 (T6)
#
# Proves inv-api-40-message-references-envelope itself works, the same way
# self-test-fixtures.sh proves the breaking-change gate works: against
# isolated fixtures, independent of whether any real service's asyncapi.yaml
# happens to comply yet. Run from an image with the `spectral` binary
# ($SPECTRAL_IMAGE in .gitlab-ci.yml).
#
# Known, named consequence (not silently avoided, CS-50's spirit): every
# INV-T-2849 per-service placeholder message currently fails this rule
# (no contentType set) until each service's own change sets
# `contentType: application/cloudevents+json` on its real messages — a
# one-line, per-service fast-follow, out of T6's declared scope
# (service-runtime/schemas/*, spectral/ only).
set -u
cd "$(dirname "$0")"
failed=0

echo "gst-ci: self-test — a message with contentType: application/cloudevents+json must PASS"
if ! spectral lint --ruleset ruleset.yaml --fail-severity error fixtures/asyncapi-message-with-envelope.yaml >/tmp/with.out 2>&1; then
  echo "gst-ci: FAIL — a compliant message was rejected"
  cat /tmp/with.out
  failed=1
fi

echo "gst-ci: self-test — a message without contentType must FAIL on inv-api-40-message-references-envelope"
if spectral lint --ruleset ruleset.yaml --fail-severity error fixtures/asyncapi-message-without-envelope.yaml >/tmp/without.out 2>&1; then
  echo "gst-ci: FAIL — a non-compliant message was accepted"
  cat /tmp/without.out
  failed=1
elif ! grep -q 'inv-api-40-message-references-envelope' /tmp/without.out; then
  echo "gst-ci: FAIL — the fixture failed lint, but not on inv-api-40-message-references-envelope:"
  cat /tmp/without.out
  failed=1
fi

if [ "$failed" -eq 0 ]; then
  echo "gst-ci: inv-api-40-message-references-envelope self-test passed (fails without contentType, passes with it)."
fi
exit $failed
