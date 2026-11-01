#!/usr/bin/env sh
# spectral/self-test-flow-resumption.sh — INV-T-14114 (lesson P20): proves check-flow-resumption.py fires.
# A passing root (the five services, minimal openapi.yaml each) and one case for each finding; a failing
# case must print its finding as a whole line. Every root is generated in a temporary directory and
# removed by the trap.
set -u
cd "$(dirname "$0")"
CHECK="$PWD/check-flow-resumption.py"
failed=0
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
H='flow\tstep\tservice\toperationId\tgap'
PA=pack-authoring-to-activation; OG=onboarding-to-go-live; PR=period-readiness-to-filing
BASE="$H\n$PA\tdraft\trules\tgetMappingPack\t-\n$PA\tdry-run-result\trules\tgetDryRun\t-\n$PA\tapproval\trules\tgetMappingPack\t-\n$PA\tacknowledgements\trules\tUNREAD\tINV-Q-14102\n$OG\tcoverage-map\tingestion\tUNREAD\tINV-Q-14102\n$OG\tgap-report\tmasterdata\tgetGapReport\t-\n$OG\tcertification-results\ttenancy\tUNREAD\tINV-Q-14102\n$PR\tverdict\tsubmission\tgetReadiness\t-\n$PR\tdrain-progress\tsubmission\tUNREAD\tINV-Q-14102\n$PR\tresidual-list\tsubmission\tUNREAD\tINV-Q-14102\ncompensation\taffected-population-definition\trules\tUNREAD\tINV-Q-14102\n"
get() { printf '  %s:\n    get:\n      operationId: %s\n      responses:\n        "%s": {description: ok%s}\n' "$1" "$2" "$3" "$4"; }
mkroot() { # name, table (printf format) -> $T/<name>
  d="$T/$1"; mkdir -p "$d/catalogue"
  for s in rules ingestion masterdata tenancy submission; do mkdir -p "$d/$s"; printf 'openapi: 3.1.0\ninfo: {title: %s, version: 1.0.0}\npaths:\n' "$s" > "$d/$s/openapi.yaml"; done
  B=', content: {application/json: {schema: {type: object}}}'
  { get /p getMappingPack 200 "$B"; get /d getDryRun 200 "$B"; } >> "$d/rules/openapi.yaml"
  get /g getGapReport 200 "$B" >> "$d/masterdata/openapi.yaml"
  { get /r getReadiness 200 "$B"; printf '  /drain:\n    post: {operationId: drainPeriod, responses: {"200": {description: ok, content: {application/json: {schema: {type: object}}}}}}\n'; } >> "$d/submission/openapi.yaml"
  for s in ingestion tenancy; do sed -i 's/^paths:$/paths: {}/' "$d/$s/openapi.yaml"; done
  printf "$2" > "$d/catalogue/flow-resumption.tsv"
}
run() { python3 "$CHECK" "$T/$1" 2>&1; }
expect_pass() {
  out=$(run "$1"); rc=$?
  if [ $rc -ne 0 ] || [ "$out" != "flow resumption: 11 points, 6 unread" ]; then echo "gst-ci: FAIL — '$1' rejected:"; echo "$out"; failed=1; else echo "gst-ci: self-test '$1' passed as expected"; fi
}
expect_fail() { # name, finding (whole line)
  out=$(run "$1"); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' was accepted"; failed=1
  elif printf '%s\n' "$out" | grep -Fxq -- "$2" && printf '%s\n' "$out" | head -1 | grep -Fxq 'gst-ci: flow-resumption FAIL'; then echo "gst-ci: self-test '$1' failed as expected"
  else echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1; fi
}
P='flow-resumption.tsv: '
mkroot pass "$BASE"; expect_pass pass
# a case is the base table with one sed edit; the second argument is the sed script
mut() { n=$1; mkroot "$n" "$BASE"; sed -i "$2" "$T/$n/catalogue/flow-resumption.tsv"; }
TSV() { echo "$T/$1/catalogue/flow-resumption.tsv"; }
mut header '1s/flow/Flow/';                           expect_fail header "${P}the header differs from flow, step, service, operationId, gap"
mut four-columns '2s/\t-$//';                         expect_fail four-columns "${P}line 2 has 4 columns, not five"
mut six-columns '2s/$/\tx/';                          expect_fail six-columns "${P}line 2 has 6 columns, not five"
mut empty-cell '2s/\trules\t/\t\t/';                  expect_fail empty-cell "${P}line 2 has an empty cell"
mut unknown-point '2s/\tdraft\t/\tdrafts\t/';         expect_fail unknown-point "${P}line 2: ($PA, drafts) is not a resumption point of INV-REQ-1008"
mut step-lookalike '3s/dry-run-result/dry-run-results/'; expect_fail step-lookalike "${P}line 3: ($PA, dry-run-results) is not a resumption point of INV-REQ-1008"
mut flow-case "2s/^$PA/Pack-authoring-to-activation/"; expect_fail flow-case "${P}line 2: (Pack-authoring-to-activation, draft) is not a resumption point of INV-REQ-1008"
mut twelfth '$a compensation\trollback\trules\tgetMappingPack\t-'; sed -i '$s/^ //' "$(TSV twelfth)"; expect_fail twelfth "${P}line 13: (compensation, rollback) is not a resumption point of INV-REQ-1008"
mut repeated '$a '"$PA"'\tdraft\trules\tgetMappingPack\t-'; sed -i '$s/^ //' "$(TSV repeated)"; expect_fail repeated "${P}line 13 repeats line 2"
mut wrong-owner '2s/\trules\t/\tmasterdata\t/';       expect_fail wrong-owner "${P}line 2: service 'masterdata' is not 'rules', the owner of $PA draft"
mut service-space '2s/\trules\t/\trules \t/';         expect_fail service-space "${P}line 2: service 'rules ' is not 'rules', the owner of $PA draft"
mkroot no-openapi "$BASE"; rm "$T/no-openapi/masterdata/openapi.yaml"; expect_fail no-openapi "${P}line 7: service 'masterdata' has no openapi.yaml"
mut unknown-op '3s/getDryRun/getDryRuns/';            expect_fail unknown-op "${P}line 3: operationId 'getDryRuns' is not an operationId of rules/openapi.yaml"
mut unknown-op-case '2s/getMappingPack/getmappingpack/'; expect_fail unknown-op-case "${P}line 2: operationId 'getmappingpack' is not an operationId of rules/openapi.yaml"
mut other-service '2s/getMappingPack/getReadiness/';  expect_fail other-service "${P}line 2: operationId 'getReadiness' is not an operationId of rules/openapi.yaml"
mut post-op-verdict '9s/getReadiness/drainPeriod/';   expect_fail post-op-verdict "${P}line 9: operationId 'drainPeriod' of submission/openapi.yaml is not a GET operation"
mut post-op-drain '10s/UNREAD\tINV-Q-14102/drainPeriod\t-/'; expect_fail post-op-drain "${P}line 10: operationId 'drainPeriod' of submission/openapi.yaml is not a GET operation"
mkroot no-body "$BASE"; sed -i 's/"200": {description: ok, content.*$/"204": {description: none}/' "$T/no-body/rules/openapi.yaml"; expect_fail no-body "${P}line 3: operationId 'getDryRun' of rules/openapi.yaml has no 2xx response with a body"
mkroot no-content "$BASE"; sed -i '/operationId: getDryRun/,$ s/, content: {application\/json: {schema: {type: object}}}//' "$T/no-content/rules/openapi.yaml"; expect_fail no-content "${P}line 3: operationId 'getDryRun' of rules/openapi.yaml has no 2xx response with a body"
mut unread-unpinned '9s/getReadiness\t-/UNREAD\tINV-Q-14102/'; expect_fail unread-unpinned "${P}line 9: $PR verdict is not pinned UNREAD"
mut gap-mapped '2s/-$/INV-Q-14102/';                 expect_fail gap-mapped "${P}line 2: gap 'INV-Q-14102' must be '-'"
mut gap-unread '5s/INV-Q-14102$/-/';                  expect_fail gap-unread "${P}line 5: gap '-' must be 'INV-Q-14102'"
mut missing-point '/\tcoverage-map\t/d';              expect_fail missing-point "${P}resumption point ($OG, coverage-map) has no line"
# each pinned point mapped in turn: line number, flow, step, service
for spec in "5 $PA acknowledgements rules" "6 $OG coverage-map ingestion" "8 $OG certification-results tenancy" "10 $PR drain-progress submission" "11 $PR residual-list submission" "12 compensation affected-population-definition rules"; do
  set -- $spec; n=case-$3
  mkroot "$n" "$BASE"; sed -i "$1"'s/UNREAD\tINV-Q-14102/getMappingPack\t-/' "$(TSV $n)"
  expect_fail "$n" "${P}line $1: $2 $3 is pinned UNREAD but maps to operationId 'getMappingPack'"
done
# table and file faults
mkroot no-table "$BASE"; rm "$(TSV no-table)"; expect_fail no-table "${P}cannot be read: FileNotFoundError"
mkroot empty-table ""; expect_fail empty-table "${P}is empty"
mkroot bad-yaml "$BASE"; printf 'paths: [unclosed\n' > "$T/bad-yaml/submission/openapi.yaml"
out=$(run bad-yaml); [ $? -ne 0 ] && printf '%s\n' "$out" | grep -q "^${P}submission/openapi.yaml cannot be parsed" && echo "gst-ci: self-test 'bad-yaml' failed as expected" || { echo "gst-ci: FAIL — 'bad-yaml':"; echo "$out"; failed=1; }
# the real repository
if python3 "$CHECK" .. >/dev/null 2>&1; then echo "gst-ci: self-test 'repository' passed as expected"; else echo "gst-ci: FAIL — the repository was rejected"; failed=1; fi
[ "$failed" -eq 0 ] && echo "gst-ci: check-flow-resumption.py self-test passed."
exit $failed
