#!/usr/bin/env sh
# spectral/self-test-no-typed-confirmation.sh — INV-T-15826 (lesson P20): proves check-no-typed-confirmation.py fires.
# A passing root (a minimal submission/openapi.yaml) and one case for each finding, each case changing
# the clean tree alone; a failing case must print its finding as a whole line. Every root is generated
# in a temporary directory and removed by the trap.
set -u
cd "$(dirname "$0")"
CHECK="$PWD/check-no-typed-confirmation.py"
failed=0
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
OP='drainPeriod'
# mkroot name: a clean root. The optional variables change the operation alone: BODY (requestBody lines),
# XPARAM (an extra parameter line), REV (reversibility), OPID, REF2 (second parameter $ref), TWICE (a second path),
# PITEM (path-item parameters, placed beside post:), PIN (the in: of PeriodRef). Plain sh and POSIX sed only (the CI image has BusyBox).
mkroot() {
  d="$T/$1"; mkdir -p "$d/spectral" "$d/submission" "$d/other"
  printf 'directory\toperationId\nsubmission\tdrainPeriod\n' > "$d/spectral/routine-irreversible-operations.tsv"
  cat > "$d/submission/openapi.yaml" <<YAML
openapi: 3.1.0
info: {title: submission, version: 1.0.0}
paths:
  /v1/entities/{entityRef}/periods/{periodRef}/drain:
${PITEM:-    # no path-item parameters}
    post:
      operationId: ${OPID:-drainPeriod}
      x-operation: {reversibility: ${REV:-IRREVERSIBLE}}
${BODY:-      # no request body}
      parameters:
        - \$ref: '#/components/parameters/EntityRef'
        - \$ref: '${REF2:-#/components/parameters/PeriodRef}'
${XPARAM:-        # no further parameter}
      responses:
        "200": {description: ok}
${TWICE:-  # no second path}
  /v1/replay:
    post:
      operationId: replaySubmissionsByScope
      x-operation: {reversibility: IRREVERSIBLE}
      requestBody:
        content: {application/json: {schema: {type: object}}}
      responses:
        "200": {description: ok}
components:
  parameters:
    EntityRef: {name: entityRef, in: path, required: true, schema: {type: string}}
    PeriodRef: {name: periodRef, in: ${PIN:-path}, required: true, schema: {type: string}}
YAML
  printf 'openapi: 3.1.0\ninfo: {title: other, version: 1.0.0}\npaths: {}\n' > "$d/other/openapi.yaml"
}
run() { python3 "$CHECK" "$T/$1" 2>&1; }
expect_pass() {
  out=$(run "$1"); rc=$?
  if [ $rc -ne 0 ] || [ "$out" != "no-typed-confirmation: 1 rows" ]; then echo "gst-ci: FAIL — '$1' rejected:"; echo "$out"; failed=1; else echo "gst-ci: self-test '$1' passed as expected"; fi
}
expect_fail() { # name, finding line (printed with the two-space dash prefix)
  out=$(run "$1"); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' was accepted"; failed=1
  elif printf '%s\n' "$out" | grep -Fxq -- "  - $2" && printf '%s\n' "$out" | head -1 | grep -Fxq 'no-typed-confirmation FAILED:'; then echo "gst-ci: self-test '$1' failed as expected"
  else echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1; fi
}
Y() { echo "$T/$1/submission/openapi.yaml"; }
W='submission/openapi.yaml: operation drainPeriod'
mkroot pass; expect_pass pass
(BODY='      requestBody:
        content: {application/json: {schema: {type: object, properties: {confirmationPhrase: {type: string}}}}}' mkroot body)
expect_fail body "$W: has a requestBody, which could carry a typed confirmation phrase"
(XPARAM='        - {name: confirm, in: query, schema: {type: string}}' mkroot query)
expect_fail query "$W: parameter 2 is in 'query', only path parameters are allowed"
(XPARAM='        - {name: X-Confirm, in: header, schema: {type: string}}' mkroot header)
expect_fail header "$W: parameter 2 is in 'header', only path parameters are allowed"
(XPARAM='        - {name: c, in: cookie, schema: {type: string}}' mkroot cookie)
expect_fail cookie "$W: parameter 2 is in 'cookie', only path parameters are allowed"
(PIN=query mkroot ref-query)
expect_fail ref-query "$W: parameter PeriodRef is in 'query', only path parameters are allowed"
(REV=COMPENSABLE mkroot compensable)
expect_fail compensable "$W: x-operation.reversibility 'COMPENSABLE' is not IRREVERSIBLE: the row names a routine irreversible operation"
(REV='null' mkroot no-reversibility)
expect_fail no-reversibility "$W: x-operation.reversibility None is not IRREVERSIBLE: the row names a routine irreversible operation"
(OPID=drainPeriods mkroot renamed)
expect_fail renamed "$W: missing from that file"
(TWICE='  /v1/second:
    post:
      operationId: drainPeriod
      x-operation: {reversibility: IRREVERSIBLE}
      responses:
        "200": {description: ok}' mkroot twice)
expect_fail twice "$W: declared in 2 operations"
(PITEM='    parameters:
      - {name: confirm, in: query, schema: {type: string}}' mkroot path-item-query)
expect_fail path-item-query "$W: path-item parameter 0 is in 'query', only path parameters are allowed"
(PITEM='    parameters:
      - $ref: '"'"'#/components/parameters/Nope'"'"'' mkroot path-item-bad-ref)
expect_fail path-item-bad-ref "$W: path-item parameter 0 \$ref '#/components/parameters/Nope' cannot be resolved"
(PITEM='    parameters:
      - {name: ok, in: path, required: true, schema: {type: string}}' mkroot path-item-path)
expect_pass path-item-path
(REF2='#/components/parameters/Nope' mkroot bad-ref)
expect_fail bad-ref "$W: parameter 1 \$ref '#/components/parameters/Nope' cannot be resolved"
(REF2='other.yaml#/components/parameters/PeriodRef' mkroot ext-ref)
expect_fail ext-ref "$W: parameter 1 \$ref 'other.yaml#/components/parameters/PeriodRef' cannot be resolved"
# table and file faults
mkroot header-diff; sed -i '1s/directory/Directory/' "$T/header-diff/spectral/routine-irreversible-operations.tsv"
expect_fail header-diff "routine-irreversible-operations.tsv: the header line must be directory	operationId"
mkroot three-fields; printf 'submission\tdrainPeriod\tx\n' >> "$T/three-fields/spectral/routine-irreversible-operations.tsv"
expect_fail three-fields "routine-irreversible-operations.tsv: line 3 has 3 fields, expected 2"
mkroot dup-row; printf 'submission\tdrainPeriod\n' >> "$T/dup-row/spectral/routine-irreversible-operations.tsv"
expect_fail dup-row "routine-irreversible-operations.tsv: line 3 is a duplicate row"
mkroot no-table; rm "$T/no-table/spectral/routine-irreversible-operations.tsv"
out=$(run no-table); [ $? -ne 0 ] && printf '%s\n' "$out" | grep -q '^  - routine-irreversible-operations.tsv: unreadable' && echo "gst-ci: self-test 'no-table' failed as expected" || { echo "gst-ci: FAIL — 'no-table':"; echo "$out"; failed=1; }
mkroot bad-yaml; printf 'paths: [unclosed\n' > "$(Y bad-yaml)"
out=$(run bad-yaml); [ $? -ne 0 ] && printf '%s\n' "$out" | grep -q '^  - submission/openapi.yaml: unreadable YAML' && echo "gst-ci: self-test 'bad-yaml' failed as expected" || { echo "gst-ci: FAIL — 'bad-yaml':"; echo "$out"; failed=1; }
mkroot missing-yaml; rm "$(Y missing-yaml)"
out=$(run missing-yaml); [ $? -ne 0 ] && printf '%s\n' "$out" | grep -q '^  - submission/openapi.yaml: unreadable YAML' && echo "gst-ci: self-test 'missing-yaml' failed as expected" || { echo "gst-ci: FAIL — 'missing-yaml':"; echo "$out"; failed=1; }
# look-alikes: a body-carrying drainPeriod in another directory, and the unlisted replaySubmissionsByScope (has a body), leave the row passing
mkroot other-dir; printf 'openapi: 3.1.0\ninfo: {title: other, version: 1.0.0}\npaths:\n  /x:\n    post:\n      operationId: drainPeriod\n      requestBody: {content: {application/json: {schema: {type: object}}}}\n      responses:\n        "200": {description: ok}\n' > "$T/other-dir/other/openapi.yaml"
expect_pass other-dir
# the real repository
if python3 "$CHECK" .. >/dev/null 2>&1; then echo "gst-ci: self-test 'repository' passed as expected"; else echo "gst-ci: FAIL — the repository was rejected"; failed=1; fi
[ "$failed" -eq 0 ] && echo "gst-ci: check-no-typed-confirmation.py self-test passed."
exit $failed
