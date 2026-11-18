#!/usr/bin/env sh
# spectral/self-test-request-enums.sh — INV-T-25007 (INV-AC-25006): proves check-request-enums.py fires.
# Each fixture directory is the --discover ROOT. Rejected: a request enum with x-extensible-enum, a marked
# schema shared by a request and a response. Accepted: a response enum with the marker, a closed request
# enum, a same-named closed request enum on another path, look-alike keys. Then three mutation probes.
set -u
cd "$(dirname "$0")"
F=fixtures/request-enums
failed=0
for c in pass-response-marker pass-request-closed pass-other-path pass-lookalike-key; do
  out=$(python3 check-request-enums.py --discover "$F/$c" 2>&1) || { echo "gst-ci: FAIL — '$c' rejected:"; echo "$out"; failed=1; }
done
expect_fail() { # fixture-root expected-text
  out=$(python3 check-request-enums.py --discover "$1" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail "$F/fail-request-marker" "CreateThingRequest: kind: request enumeration carries x-extensible-enum"
expect_fail "$F/fail-shared-schema" "components.schemas.Kind: (schema root): request enumeration carries x-extensible-enum"
M=$(mktemp -d "${TMPDIR:-/tmp}/request-enums.XXXXXX")
trap 'rm -rf "$M"' EXIT
probe() { # name sed-expr fixture: apply to a copy of a passing fixture; the checker must then reject
  rm -rf "$M/p"; mkdir -p "$M/p"; cp -R "$F/$3/." "$M/p/"
  sed -i "$2" "$M/p/svc/openapi.yaml"
  if python3 check-request-enums.py --discover "$M/p" >/dev/null 2>&1; then
    echo "gst-ci: FAIL — probe '$1' was accepted"; failed=1
  else echo "gst-ci: self-test probe '$1' rejected as expected"; fi
}
probe marker-true 's/^\(          enum: \[A, B\]\)$/\1\n          x-extensible-enum: true/' pass-request-closed
probe marker-list 's/^\(          enum: \[A, B\]\)$/\1\n          x-extensible-enum: [A, B, C]/' pass-request-closed
probe request-points-at-response "10s|CreateThingRequest|ThingResponse|" pass-response-marker
[ "$failed" -eq 0 ] && echo "gst-ci: check-request-enums.py self-test passed."
exit $failed
