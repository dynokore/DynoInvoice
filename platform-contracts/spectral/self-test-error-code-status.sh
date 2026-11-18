#!/usr/bin/env sh
# spectral/self-test-error-code-status.sh — INV-T-23085: proves check-error-code-status.py fires.
# Passing cases and one case per failure. Every case is generated in a temporary directory that is
# removed at the end of the script.
set -u
cd "$(dirname "$0")"
CHECK="$(pwd)/check-error-code-status.py"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
failed=0
TAB=$(printf '\t')
mkcat() { # name -> catalogue: 409, double 503, HTTPS, 4090, none, none
  mkdir -p "$T/$1/catalogue/errors" "$T/$1/spectral"
  cat > "$T/$1/catalogue/errors/error-codes.yaml" <<'YAML'
error_codes:
- code: A-409
  condition: Stale (HTTP 409; INV-Q-1)
- code: B-503
  condition: Down, HTTP 503, retry later (HTTP 503)
- code: D-HTTPS
  condition: Uses HTTPS 409 only
- code: E-4090
  condition: Odd HTTP 4090
- code: F-NONE
  condition: No status stated
- code: G-NONE
  condition: No status either
YAML
  printf 'code\tstatus\tsource\n' > "$T/$1/spectral/error-code-status.tsv"
  printf 'directory\toperationId\tcode\treason\n' > "$T/$1/spectral/error-code-status-baseline.tsv"
}
mkopenapi() { # name, dir, x-error-codes value, responses (YAML block text, 8-space indented)
  mkdir -p "$T/$1/$2"
  printf 'openapi: 3.1.0\ninfo: {title: f, version: 1.0.0}\npaths:\n  /x:\n    post:\n      operationId: doThing\n      x-error-codes: %s\n      responses:\n%b' "$3" "$4" > "$T/$1/$2/openapi.yaml"
}
R200='        "200": {description: ok}\n'
mk() { mkcat "$1"; mkopenapi "$1" svc "$2" "$3"; } # name, codes, responses
table() { printf '%b' "$2" >> "$T/$1/spectral/error-code-status.tsv"; }
base() { printf '%b' "$2" >> "$T/$1/spectral/error-code-status-baseline.tsv"; }
expect_pass() {
  out=$(python3 "$CHECK" "$T/$1" 2>&1) || { echo "gst-ci: FAIL — '$1' was rejected:"; echo "$out"; failed=1; return; }
  echo "gst-ci: self-test '$1' passed as expected"
}
expect_fail() { # name, text
  out=$(python3 "$CHECK" "$T/$1" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
NR="has status 409 but the operation has no response 409"

mk pass-503-twice '[B-503]' "$R200"'        "503": {description: x}\n';  expect_pass pass-503-twice
mk pass-quoted '[A-409]' "$R200"'        "409": {description: x}\n';     expect_pass pass-quoted
mk pass-int '[A-409]' "$R200"'        409: {description: x}\n';          expect_pass pass-int
mk pass-table '[F-NONE]' "$R200"'        "418": {description: x}\n';     table pass-table 'F-NONE\t418\tINV-Q-5\n'; expect_pass pass-table
mk pass-baseline '[A-409]' "$R200";   base pass-baseline 'svc\tdoThing\tA-409\tstatus_not_declared\n'; expect_pass pass-baseline

mk only-default '[A-409]' "$R200"'        default: {description: x}\n';  expect_fail only-default "operation doThing: code A-409 $NR"
mk range-4xx '[A-409]' "$R200"'        4XX: {description: x}\n';         expect_fail range-4xx "$NR"
mk key-4090 '[A-409]' "$R200"'        '"'4090'"': {description: x}\n';   expect_fail key-4090 "$NR"
mk key-space '[A-409]' "$R200"'        "409 ": {description: x}\n';      expect_fail key-space "$NR"
mk other-operation '[A-409]' "$R200"
printf '  /y:\n    post:\n      operationId: other\n      responses:\n        "409": {description: x}\n' >> "$T/other-operation/svc/openapi.yaml"
expect_fail other-operation "$NR"
mk components-only '[A-409]' "$R200"
printf 'components:\n  responses:\n    "409": {description: x}\n' >> "$T/components-only/svc/openapi.yaml"
expect_fail components-only "$NR"
mk no-status '[F-NONE]' "$R200";                                         expect_fail no-status "code F-NONE has no status"
mk two-values '[C-TWO]' "$R200"; printf -- '- code: C-TWO\n  condition: Either HTTP 409 or HTTP 422\n' >> "$T/two-values/catalogue/errors/error-codes.yaml";                                        expect_fail two-values "more than one different status"
mk https '[D-HTTPS]' "$R200";                                            expect_fail https "code D-HTTPS has no status"
mk http-4090 '[E-4090]' "$R200";                                         expect_fail http-4090 "code E-4090 has no status"
mk table-differs '[A-409]' "$R200"'        "409": {description: x}\n';   table table-differs 'A-409\t422\tINV-Q-5\n'; expect_fail table-differs "one source per code"
mk table-unknown '[F-NONE]' "$R200";                                     table table-unknown 'Z-999\t418\tINV-Q-5\n'; expect_fail table-unknown "is not a code of the catalogue"
mk table-source '[F-NONE]' "$R200"'        "418": {description: x}\n';   table table-source 'F-NONE\t418\tQ-5\n'; expect_fail table-source "does not match ^INV-(Q|REQ)-[0-9]+\$"
mk table-second '[F-NONE]' "$R200"'        "418": {description: x}\n';   table table-second 'F-NONE\t418\tINV-Q-5\nF-NONE\t418\tINV-Q-6\n'; expect_fail table-second "second row for code 'F-NONE'"
mk table-status '[F-NONE]' "$R200";                                      table table-status 'F-NONE\t99\tINV-Q-5\n'; expect_fail table-status "is not three digits 100 to 599"
mk table-header '[A-409]' "$R200"'        "409": {description: x}\n';    printf 'code\tstatus\n' > "$T/table-header/spectral/error-code-status.tsv"; expect_fail table-header "the header must be exactly"
mk stale-base '[A-409]' "$R200"'        "409": {description: x}\n';      base stale-base 'svc\tdoThing\tA-409\tstatus_not_declared\n'; expect_fail stale-base "stale baseline row"
mk stale-reason '[A-409]' "$R200";                                       base stale-reason 'svc\tdoThing\tA-409\tno_status\n'; expect_fail stale-reason "stale baseline row"
mk unknown-op '[A-409]' "$R200";                                         base unknown-op 'svc\tdoThings\tA-409\tstatus_not_declared\n'; expect_fail unknown-op "unknown baseline row"
mk unknown-code '[A-409]' "$R200";                                       base unknown-code 'svc\tdoThing\tB-503\tstatus_not_declared\n'; expect_fail unknown-code "unknown baseline row"
mk look-alike-dir '[A-409]' "$R200"; mkopenapi look-alike-dir svc2 '[]' "$R200"; base look-alike-dir 'svc2\tdoThing\tA-409\tstatus_not_declared\n'; expect_fail look-alike-dir "unknown baseline row"
mk no-such-dir '[A-409]' "$R200";                                        base no-such-dir 'nodir\tdoThing\tA-409\tstatus_not_declared\n'; expect_fail no-such-dir "is not a contract directory"
mk dup-base '[A-409]' "$R200";   base dup-base 'svc\tdoThing\tA-409\tstatus_not_declared\nsvc\tdoThing\tA-409\tstatus_not_declared\n'; expect_fail dup-base "duplicate baseline row"
mk reason-other '[A-409]' "$R200";                                       base reason-other 'svc\tdoThing\tA-409\tother\n'; expect_fail reason-other "reason 'other'"
mk header-five '[A-409]' "$R200";   printf 'directory\toperationId\tcode\treason\textra\n' > "$T/header-five/spectral/error-code-status-baseline.tsv"; expect_fail header-five "the header must be exactly"
mk no-catalogue '[A-409]' "$R200"; rm -rf "$T/no-catalogue/catalogue";   expect_fail no-catalogue "the catalogue does not exist"
mk empty-catalogue '[A-409]' "$R200"; printf 'error_codes: []\n' > "$T/empty-catalogue/catalogue/errors/error-codes.yaml"; expect_fail empty-catalogue "non-empty list"
mkcat no-contract;                                                       expect_fail no-contract "no */openapi.yaml found"
mk bad-contract '[A-409]' "$R200"; printf 'a: [unclosed\n' > "$T/bad-contract/svc/openapi.yaml"; expect_fail bad-contract "the contract cannot be parsed"
mk string-value 'A-409' "$R200";                                         expect_fail string-value "x-error-codes is not a list"
mk int-member '[409]' "$R200";                                           expect_fail int-member "is not a string"
# Example fidelity (INV-T-24693): the example code of an inline problem+json response.
EX() { # status-key, code -> response block with a problem+json example
  printf '        %s:\n          description: x\n          content:\n            application/problem+json:\n              example: {code: %s}\n' "$1" "$2"
}
mk ex-pass '[A-409]' "$R200$(EX '"409"' A-409)";                         expect_pass ex-pass
mk ex-pass-nostatus '[F-NONE]' "$R200$(EX '"418"' F-NONE)";              table ex-pass-nostatus 'F-NONE\t418\tINV-Q-5\n'; expect_pass ex-pass-nostatus
mk ex-pass-nostatus-any '[F-NONE]' "$R200$(EX default F-NONE)";    base ex-pass-nostatus-any 'svc\tdoThing\tF-NONE\tno_status\n'; expect_pass ex-pass-nostatus-any
mk ex-pass-ref '[A-409]' "$R200"'        "409":\n          $ref: "#/components/responses/E"\n'; expect_pass ex-pass-ref
mk ex-notmember '[A-409]' "$R200$(EX '"409"' A-409)"; sed -i 's/{code: A-409}/{code: B-409}/' "$T/ex-notmember/svc/openapi.yaml"
printf -- '- code: B-409\n  condition: Other (HTTP 409)\n' >> "$T/ex-notmember/catalogue/errors/error-codes.yaml"
expect_fail ex-notmember "response '409' example code B-409 is not a member of x-error-codes"
mk ex-otherstatus '[A-409, B-503]' "$R200$(EX '"422"' A-409)";            expect_fail ex-otherstatus "code A-409 has status 409 but the response key is 422"
mk ex-default '[A-409]' "$R200$(EX default A-409)";                      expect_fail ex-default "code A-409 has status 409 but the response key is default"
mk ex-quotedkey '[A-409]' "$R200$(EX "'409 '" A-409)";                   expect_fail ex-quotedkey "code A-409 has status 409 but the response key is 409 "
mk ex-named '[A-409]' "$R200"'        "409":\n          description: x\n          content:\n            application/problem+json:\n              examples:\n                one:\n                  value: {code: B-503}\n'
expect_fail ex-named "example code B-503 is not a member of x-error-codes"
mk ex-nonstring '[A-409]' "$R200$(EX '"409"' 409)";                      expect_fail ex-nonstring "code 409 is not a string"
mk ex-nolist '[]' "$R200$(EX '"409"' A-409)"; sed -i '/x-error-codes/d' "$T/ex-nolist/svc/openapi.yaml"; expect_fail ex-nolist "example code A-409 is not a member of x-error-codes"
mk ex-dir2 '[A-409]' "$R200"; mkopenapi ex-dir2 svc2 '[A-409]' "$R200$(EX '"409"' B-503)"; expect_fail ex-dir2 "svc2/openapi.yaml: operation doThing: response '409' example code B-503 is not a member"
[ "$failed" -eq 0 ] && echo "gst-ci: check-error-code-status.py self-test passed."
exit $failed
