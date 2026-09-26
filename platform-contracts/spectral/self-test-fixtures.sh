#!/usr/bin/env sh
# spectral/self-test-fixtures.sh — INV-T-2849 (T5)
#
# Proves the breaking-change gate itself works, independent of whether any
# real merge request happens to touch a contract: oasdiff must fail on the
# fixture that removes a response field, and must pass an additive one;
# check-payload-compat.py the same, for an event payload schema. Run once,
# from an image that has both `oasdiff` and `python3` (ci/.gitlab-ci.yml's
# oasdiff-breaking-gate job; asyncapi diff's own payload-diff gap is exactly
# why check-payload-compat.py exists, so its fixture is tested here too rather
# than needing the asyncapi/cli image as well).
set -u
cd "$(dirname "$0")"
failed=0

echo "gst-ci: self-test — oasdiff must FAIL on a removed response field"
if oasdiff breaking fixtures/openapi-before.yaml fixtures/openapi-after-breaking.yaml --fail-on ERR; then
  echo "gst-ci: FAIL — oasdiff did not detect the breaking fixture"
  failed=1
fi

echo "gst-ci: self-test — oasdiff must PASS an additive-only change"
if ! oasdiff breaking fixtures/openapi-before.yaml fixtures/openapi-after-additive.yaml --fail-on ERR; then
  echo "gst-ci: FAIL — oasdiff flagged an additive-only change as breaking"
  failed=1
fi

echo "gst-ci: self-test — check-payload-compat.py must FAIL on a removed field"
if python3 check-payload-compat.py fixtures/payload-before.schema.json fixtures/payload-after-breaking.schema.json; then
  echo "gst-ci: FAIL — check-payload-compat.py did not detect the breaking fixture"
  failed=1
fi

echo "gst-ci: self-test — check-payload-compat.py must PASS an additive-only change"
if ! python3 check-payload-compat.py fixtures/payload-before.schema.json fixtures/payload-after-additive.schema.json; then
  echo "gst-ci: FAIL — check-payload-compat.py flagged an additive-only change as breaking"
  failed=1
fi

if [ "$failed" -eq 0 ]; then
  echo "gst-ci: breaking-change gate self-test passed (fails on breaking, passes on additive)."
fi
exit $failed
