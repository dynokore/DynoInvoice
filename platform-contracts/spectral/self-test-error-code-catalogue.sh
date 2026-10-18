#!/usr/bin/env sh
# spectral/self-test-error-code-catalogue.sh — INV-T-6947 (INV-Q-7062 A, lesson P20): proves
# check-error-code-catalogue.py fires. One passing case and one case for each failure. Every case is
# generated in a temporary directory that is removed at the end of the script.
set -u
cd "$(dirname "$0")"
CHECK="$(pwd)/check-error-code-catalogue.py"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
failed=0
mkcat() { # name -> $T/<name>/catalogue/errors/error-codes.yaml with two codes; SUB-099 only in a condition
  mkdir -p "$T/$1/catalogue/errors"
  cat > "$T/$1/catalogue/errors/error-codes.yaml" <<'YAML'
error_codes:
- code: SUB-021
  condition: Withdrawal refused
- code: SRT-001
  condition: Bad request, not SUB-099
YAML
}
mkopenapi() { # name, dir, x-error-codes line (a YAML flow value or block)
  mkdir -p "$T/$1/$2"
  printf 'openapi: 3.1.0\ninfo: {title: f, version: 1.0.0}\npaths:\n  /x:\n    post:\n      operationId: doThing\n      description: refused with SUB-099\n      x-error-codes: %s\n      responses:\n        "200": {description: ok}\n' "$3" > "$T/$1/$2/openapi.yaml"
}
mk() { mkcat "$1"; mkopenapi "$1" svc "$2"; }
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
NC="is not a code of the catalogue"
mk pass '[SUB-021, SRT-001]';             expect_pass pass
mk empty-list '[]';                       expect_pass empty-list
mk unknown-code '[SUB-021, SUB-0210]';    expect_fail unknown-code "'SUB-0210' $NC"
mk no-dash '[SUB-21]';                    expect_fail no-dash "'SUB-21' $NC"
mk lower-case '[sub-021]';                expect_fail lower-case "'sub-021' $NC"
mk trailing-space '["SUB-021 "]';         expect_fail trailing-space "'SUB-021 ' $NC"
mk integer-member '[21]';                 expect_fail integer-member "is not a string"
mk string-value 'SUB-021';                expect_fail string-value "x-error-codes is not a list"
mk condition-only '[SUB-099]';            expect_fail condition-only "'SUB-099' $NC"
mk names-operation '[SUB-0210]';          expect_fail names-operation "operation doThing"
mk no-catalogue '[SUB-021]';              rm -rf "$T/no-catalogue/catalogue"; expect_fail no-catalogue "the catalogue does not exist"
mk empty-catalogue '[SUB-021]';           printf 'error_codes: []\n' > "$T/empty-catalogue/catalogue/errors/error-codes.yaml"; expect_fail empty-catalogue "non-empty list"
mk no-code-entry '[SUB-021]';             printf 'error_codes:\n- code: SUB-021\n- condition: no code here\n' > "$T/no-code-entry/catalogue/errors/error-codes.yaml"; expect_fail no-code-entry "entry 2 has no non-empty string code"
mkcat no-contract;                        expect_fail no-contract "no */openapi.yaml found"
mk bad-contract '[SUB-021]';              printf 'a: [unclosed\n' > "$T/bad-contract/svc/openapi.yaml"; expect_fail bad-contract "the contract cannot be parsed"
mk second-contract '[SUB-021]';           mkopenapi second-contract zsvc '[SUB-0210]'; expect_fail second-contract "zsvc/openapi.yaml: operation doThing"
mkcat nested
mkdir -p "$T/nested/svc"
printf 'openapi: 3.1.0\ninfo: {title: f, version: 1.0.0}\npaths:\n  /x:\n    x-error-codes: [SUB-0210]\n    post:\n      operationId: doThing\n      responses:\n        "200": {description: ok}\n' > "$T/nested/svc/openapi.yaml"
expect_fail nested "operation (none)"
[ "$failed" -eq 0 ] && echo "gst-ci: check-error-code-catalogue.py self-test passed."
exit $failed
