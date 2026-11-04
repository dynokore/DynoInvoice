#!/usr/bin/env sh
# spectral/self-test-hold-release-kinds.sh — INV-T-15822: proves check-hold-release-kinds.py fires.
# The clean tree (the real table and catalogue) and one case per finding, each a temporary tree changed
# alone; a failing case must print its finding as a whole line. Trees are removed by the trap.
set -u
cd "$(dirname "$0")"
CHECK="$PWD/check-hold-release-kinds.py"
REPO="$(cd .. && pwd)"
failed=0
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkroot() { mkdir -p "$T/$1/catalogue/errors"; cp "$REPO/catalogue/hold-release-kinds.tsv" "$T/$1/catalogue/"; cp "$REPO/catalogue/errors/error-codes.yaml" "$T/$1/catalogue/errors/"; }
run() { python3 "$CHECK" "$T/$1" 2>&1; }
expect_pass() {
  out=$(run "$1"); rc=$?
  if [ $rc -ne 0 ] || [ "$out" != "hold-release-kinds: 11 rows" ]; then echo "gst-ci: FAIL — '$1' rejected:"; echo "$out"; failed=1; else echo "gst-ci: self-test '$1' passed as expected"; fi
}
expect_fail() { # name, finding (whole line)
  out=$(run "$1"); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' was accepted"; failed=1
  elif printf '%s\n' "$out" | grep -Fxq -- "$2" && [ "$(printf '%s\n' "$out" | head -1)" = "hold-release-kinds FAILED:" ]; then echo "gst-ci: self-test '$1' failed as expected"
  else echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1; fi
}
P='catalogue/hold-release-kinds.tsv: '
F() { echo "$T/$1/catalogue/hold-release-kinds.tsv"; }
mut() { mkroot "$1"; sed -i "$2" "$(F "$1")"; }       # one sed edit of the table
add() { mkroot "$1"; printf "$2" >> "$(F "$1")"; }    # one appended line (printf format)
mkroot pass; expect_pass pass
mut no-mdm '/^MDM-001\t/d';          expect_fail no-mdm "${P}MDM-001 is an auto-releasing HOLD code without a kind"
mut per011-data 's/^PER-011\tELECTION/PER-011\tDATA/'; expect_fail per011-data "${P}PER-011 is DATA, the owner ruled ELECTION (INV-Q-1953)"
mut sub011-election 's/^SUB-011\tDATA/SUB-011\tELECTION/'; expect_fail sub011-election "${P}SUB-011 is ELECTION, the owner ruled DATA (INV-Q-1953)"
add stale-manual 'ING-017\tDATA\n';  expect_fail stale-manual "${P}line 13: 'ING-017' is not a HOLD code with an AUTO_RELEASE disposition (stale)"
add stale-block 'MDM-002\tDATA\n';   expect_fail stale-block "${P}line 13: 'MDM-002' is not a HOLD code with an AUTO_RELEASE disposition (stale)"
add stale-absent 'FAKE-001\tDATA\n'; expect_fail stale-absent "${P}line 13: 'FAKE-001' is not a HOLD code with an AUTO_RELEASE disposition (stale)"
add lower-case 'mdm-001\tDATA\n';    expect_fail lower-case "${P}line 13: 'mdm-001' is not a HOLD code with an AUTO_RELEASE disposition (stale)"
add trailing-space 'MDM-001 \tDATA\n'; expect_fail trailing-space "${P}line 13: 'MDM-001 ' is not a HOLD code with an AUTO_RELEASE disposition (stale)"
mut kind-space 's/^MDM-001\tDATA/MDM-001\tDATA /'; expect_fail kind-space "${P}line 4: kind 'DATA ' of 'MDM-001' is not exactly DATA or ELECTION"
mut kind-lower 's/^ING-013\tDATA/ING-013\tdata/'; expect_fail kind-lower "${P}line 2: kind 'data' of 'ING-013' is not exactly DATA or ELECTION"
add duplicate 'MDM-001\tDATA\n';     expect_fail duplicate "${P}line 13 repeats the code 'MDM-001'"
mut extra-column '4s/$/\tx/';        expect_fail extra-column "${P}line 4 has 3 fields, not two"
mut one-column '4s/\tDATA//';        expect_fail one-column "${P}line 4 has 1 fields, not two"
mut header '1s/code/Code/';          expect_fail header "${P}the header line must be exactly code<TAB>kind"
mut header-space '1s/$/ /';          expect_fail header-space "${P}the header line must be exactly code<TAB>kind"
mut crlf '4s/$/\r/';                 expect_fail crlf "${P}line 4: kind 'DATA\\r' of 'MDM-001' is not exactly DATA or ELECTION"
mkroot new-hold
cat >> "$T/new-hold/catalogue/errors/error-codes.yaml" <<'YAML'
- code: PER-099
  condition: probe
  severity: HOLD
  disposition: AUTO_RELEASE when probed
  owner: Onboarding
YAML
expect_fail new-hold "${P}PER-099 is an auto-releasing HOLD code without a kind"
mkroot no-table; rm "$(F no-table)"; out=$(run no-table); [ $? -ne 0 ] && printf '%s\n' "$out" | grep -q "^${P}cannot be read: FileNotFoundError" && echo "gst-ci: self-test 'no-table' failed as expected" || { echo "gst-ci: FAIL — 'no-table':"; echo "$out"; failed=1; }
mkroot no-catalogue; rm "$T/no-catalogue/catalogue/errors/error-codes.yaml"; out=$(run no-catalogue); [ $? -ne 0 ] && printf '%s\n' "$out" | grep -q "^catalogue/errors/error-codes.yaml: cannot be read: FileNotFoundError" && echo "gst-ci: self-test 'no-catalogue' failed as expected" || { echo "gst-ci: FAIL — 'no-catalogue':"; echo "$out"; failed=1; }
# the real repository
out=$(python3 "$CHECK" ..) && [ "$out" = "hold-release-kinds: 11 rows" ] && echo "gst-ci: self-test 'repository' passed as expected" || { echo "gst-ci: FAIL — the repository was rejected"; failed=1; }
[ "$failed" -eq 0 ] && echo "gst-ci: check-hold-release-kinds.py self-test passed."
exit $failed
