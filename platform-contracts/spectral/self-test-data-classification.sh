#!/usr/bin/env sh
# spectral/self-test-data-classification.sh — INV-T-6443 (lesson P20): proves check-data-classification.py
# fires. Every directory under fixtures/data-classification/ is one case holding required.tsv and one or more
# service directories (svc/ and, for the directory cases, a second one); the gate runs with --required on
# every service directory of the case. A pass- case must exit 0; a fail- case must exit 1 with a finding line
# that contains the unit, file or directory the case is about (the second column of EXPECT below).
set -u
cd "$(dirname "$0")"
F=fixtures/data-classification
failed=0
EXPECT="
fail-no-table|data-classification.tsv
fail-empty-table|the table is empty
fail-bad-header|header is not exactly file TAB unit TAB class
fail-extra-column|Item.id
fail-class-lowercase|identifying
fail-class-unknown|PERSONAL
fail-duplicate-row|Item.id: duplicate row
fail-missing-row|Item.name: unit without a row
fail-stale-row|Item.nothing
fail-stale-wrapper-member|doc.zzz
fail-lookalike-file|openapi.yaml.bak
fail-unrequired-dir|extra: -: -: directory is not in the required table
fail-required-not-passed|ghost: -: -: required directory was not passed
fail-required-file-missing|payload.schema.json: -: required file unreadable
fail-required-file-unparseable|payload.schema.json: -: required file unparseable
fail-required-file-no-unit|payload.schema.json: -: the file has no unit
fail-required-bad-header|header is not exactly directory TAB file
fail-below-floor|Holder.p: class NONE is below the reachable unit Party.pid
fail-below-floor-items|Holder.p: class NONE is below the reachable unit Party.pid
fail-nonlocal-ref|Item.name: \$ref 'other.yaml#/X'
fail-lookalike-envelope-ref|error-envelope.schema.json.bak
fail-ref-in-payload-unit|docUuid: \$ref inside a payload unit
fail-wrapper-free-string|doc.a: wrapper classification
fail-wrapper-three-members|doc.a: wrapper classification
fail-wrapper-wrong-class|doc.a: wrapper classification
fail-wrapper-two-members|doc.a: wrapper classification
fail-wrapper-type-integer|doc.a: wrapper classification
fail-wrapper-no-classification|doc.b
fail-defs-missing|\$defs.DataClass
fail-defs-order|\$defs.DataClass
fail-defs-lookalike|\$defs.DataClass
fail-partial-wrapper-root|doc.a: row names a unit that does not exist
"
run_case() {
  dirs=""
  for d in "$F/$1"/*/; do dirs="$dirs ${d%/}"; done
  # shellcheck disable=SC2086
  python3 check-data-classification.py --required "$F/$1/required.tsv" $dirs 2>&1
}
for d in "$F"/pass-*/; do
  c=$(basename "$d")
  out=$(run_case "$c") || { echo "gst-ci: FAIL — '$c' rejected:"; echo "$out"; failed=1; }
done
for d in "$F"/fail-*/; do
  c=$(basename "$d")
  want=$(printf '%s\n' "$EXPECT" | grep "^$c|" | head -n 1 | cut -d'|' -f2-)
  if [ -z "$want" ]; then echo "gst-ci: FAIL — fixture '$c' has no expectation"; failed=1; continue; fi
  want=$(printf '%s' "$want" | sed 's/\\\$/$/g')
  out=$(run_case "$c"); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$c' fixture was accepted"; failed=1
  elif [ $rc -ne 1 ]; then echo "gst-ci: FAIL — '$c' exited $rc, not 1:"; echo "$out"; failed=1
  else case "$out" in *"$want"*) echo "gst-ci: self-test '$c' failed as expected ($want)";;
       *) echo "gst-ci: FAIL — '$c' failed, but not with '$want':"; echo "$out"; failed=1;; esac; fi
done
n=$(printf '%s\n' "$EXPECT" | grep -c '|')
m=$(ls -d "$F"/fail-*/ | wc -l)
[ "$n" -eq "$m" ] || { echo "gst-ci: FAIL — $n expectations for $m fail- fixtures"; failed=1; }
[ "$failed" -eq 0 ] && echo "gst-ci: check-data-classification.py self-test passed."
exit $failed
