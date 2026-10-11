#!/usr/bin/env sh
# spectral/self-test-sync-edge-rule.sh — INV-T-2850 follow-up (API-08, lesson P20).
# Proves the Spectral rule inv-api-08-x-sync-edge fires on an /internal/v1
# operation without x-sync-edge or with a malformed one, and stays quiet on a
# compliant internal operation and on a non-internal operation. (The rule had
# never fired before: it selected only operations that already had the field.)
# Run from an image with the `spectral` binary ($SPECTRAL_IMAGE).
set -u
cd "$(dirname "$0")"
failed=0
lint() { spectral lint --ruleset ruleset.yaml --fail-severity error "$1" 2>&1; }
for fx in api08-missing api08-malformed; do
  out=$(lint fixtures/$fx.yaml)
  case "$out" in *inv-api-08-x-sync-edge*) echo "gst-ci: self-test $fx failed on inv-api-08-x-sync-edge as expected";;
    *) echo "gst-ci: FAIL — $fx did not trigger inv-api-08-x-sync-edge:"; echo "$out"; failed=1;; esac
done
for fx in api08-ok api08-shared-star; do
  out=$(lint fixtures/$fx.yaml)
  case "$out" in *inv-api-08-x-sync-edge*) echo "gst-ci: FAIL — $fx wrongly triggered inv-api-08-x-sync-edge:"; echo "$out"; failed=1;;
    *) echo "gst-ci: self-test $fx passes inv-api-08-x-sync-edge as expected";; esac
done
[ "$failed" -eq 0 ] && echo "gst-ci: inv-api-08-x-sync-edge self-test passed."
exit $failed
