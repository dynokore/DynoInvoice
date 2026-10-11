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
#
# INV-T-2850 follow-up (T6): also proves strip-scaffold-placeholder.py's
# narrow exemption, the same way — removing ONLY the T5 scaffold placeholder
# must pass, removing a real operation must still fail (CS-50), and a
# scaffold-shaped marker appearing only in HEAD must earn no exemption for a
# real operation's removal (the rule only ever reads BASE).
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

echo "gst-ci: self-test — strip-scaffold-placeholder.py + oasdiff must PASS when only the T5 scaffold placeholder is removed"
cp fixtures/scaffold-placeholder-base.yaml /tmp/scaffold-base-1.yaml
python3 strip-scaffold-placeholder.py widget /tmp/scaffold-base-1.yaml
if ! oasdiff breaking /tmp/scaffold-base-1.yaml fixtures/scaffold-placeholder-head.yaml --fail-on ERR; then
  echo "gst-ci: FAIL — removing only the T5 scaffold placeholder was flagged as breaking"
  failed=1
fi

echo "gst-ci: self-test — removal of a real (non-placeholder) operation must still FAIL (CS-50)"
cp fixtures/scaffold-placeholder-head.yaml /tmp/scaffold-base-2.yaml
python3 strip-scaffold-placeholder.py widget /tmp/scaffold-base-2.yaml   # no placeholder in this base: must be a no-op
if oasdiff breaking /tmp/scaffold-base-2.yaml fixtures/real-operation-removed.yaml --fail-on ERR; then
  echo "gst-ci: FAIL — removing a real operation was not detected as breaking"
  failed=1
fi

echo "gst-ci: self-test — the scaffold marker appearing only in HEAD must give no exemption"
cp fixtures/scaffold-placeholder-head.yaml /tmp/scaffold-base-3.yaml
python3 strip-scaffold-placeholder.py widget /tmp/scaffold-base-3.yaml   # base still has no placeholder: must be a no-op
if oasdiff breaking /tmp/scaffold-base-3.yaml fixtures/scaffold-marker-head-only.yaml --fail-on ERR; then
  echo "gst-ci: FAIL — a real operation's removal was exempted because HEAD (not BASE) carries the scaffold marker"
  failed=1
fi
rm -f /tmp/scaffold-base-1.yaml /tmp/scaffold-base-2.yaml /tmp/scaffold-base-3.yaml

# INV-T-3491 gate fix (INV-Q-3569): normalize-extensible-enum.py and oasdiff-exceptions.txt.
gate() { # base head [extra oasdiff args]: normalise both as run-oasdiff-gate.sh does, then diff
  python3 normalize-extensible-enum.py "$1" /tmp/gate-base.yaml
  python3 normalize-extensible-enum.py "$2" /tmp/gate-head.yaml
  shift 2
  oasdiff breaking /tmp/gate-base.yaml /tmp/gate-head.yaml --fail-on ERR "$@"
}
echo "gst-ci: self-test — a value added to an API-09 extensible response enum must PASS"
if ! gate fixtures/enum-marked-before.yaml fixtures/enum-marked-added.yaml; then
  echo "gst-ci: FAIL — a value added to an x-extensible-enum response enum was flagged as breaking"; failed=1
fi
echo "gst-ci: self-test — a value added to an unmarked response enum must still FAIL"
if gate fixtures/enum-closed-before.yaml fixtures/enum-closed-added.yaml; then
  echo "gst-ci: FAIL — a value added to an unmarked enum was accepted"; failed=1
fi
echo "gst-ci: self-test — a new request maxItems must FAIL without a recorded exception"
if gate fixtures/enum-marked-before.yaml fixtures/maxitems-set.yaml; then
  echo "gst-ci: FAIL — a new request bound was accepted with no exception"; failed=1
fi
echo "gst-ci: self-test — the same change must PASS with its one recorded exception"
if ! gate fixtures/enum-marked-before.yaml fixtures/maxitems-set.yaml --err-ignore fixtures/oasdiff-exceptions-fixture.txt; then
  echo "gst-ci: FAIL — a recorded exception did not admit its change"; failed=1
fi
rm -f /tmp/gate-base.yaml /tmp/gate-head.yaml

if [ "$failed" -eq 0 ]; then
  echo "gst-ci: breaking-change gate self-test passed (fails on breaking, passes on additive, exempts only the exact BASE-carried T5 scaffold placeholder, admits only marked extensible-enum values and recorded exceptions)."
fi
exit $failed
