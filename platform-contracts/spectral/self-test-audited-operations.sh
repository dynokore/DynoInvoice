#!/usr/bin/env sh
# spectral/self-test-audited-operations.sh — INV-T-7365 (lesson P20): proves check-audited-operations.py fires.
# A passing root (three services, one operation each, a grantRole-shaped access file and a --names-file) and one
# case for each finding; a failing case must print its finding as a whole line. Every root is generated in a
# temporary directory and removed by the trap.
set -u
cd "$(dirname "$0")"
CHECK="$PWD/check-audited-operations.py"
failed=0
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
H='listedName\toperationId\tservice\tdiscriminator'
printf 'alpha\nbeta\ngamma\ndelta\nepsilon\ninviteUser\ninviteExternalIdentity\ngrantRole\n' > "$T/names.txt"
BASE="$H"'\nalpha\talpha\tsvca\t-\nbeta\tbeta\tsvcb\t-\ngamma\tUNCONTRACTED\tsvcb\t-\ndelta\tdelta\tsvca\t-\nepsilon\tepsilon\tsvca\t-\ngrantRole\tgrantRole\taccess\t-\ninviteUser\tgrantRole\taccess\tSTAFF\ninviteExternalIdentity\tgrantRole\taccess\tEXTERNAL\n'
mkroot() { # name, table (printf format) -> $T/<name>
  mkdir -p "$T/$1/catalogue" "$T/$1/svca" "$T/$1/svcb" "$T/$1/access"
  printf "$2" > "$T/$1/catalogue/audited-operations.tsv"
  printf 'openapi: 3.1.0\ninfo: {title: a, version: 1.0.0}\npaths:\n  /a:\n    post: {operationId: alpha}\n  /d:\n    post: {operationId: delta}\n  /e:\n    post: {operationId: epsilon}\n' > "$T/$1/svca/openapi.yaml"
  printf 'openapi: 3.1.0\ninfo: {title: b, version: 1.0.0}\npaths:\n  /b:\n    post: {operationId: beta}\n' > "$T/$1/svcb/openapi.yaml"
  cat > "$T/$1/access/openapi.yaml" <<'YAML'
openapi: 3.1.0
info: {title: access, version: 1.0.0}
paths:
  /grants:
    post: {operationId: grantRole}
components:
  schemas:
    GrantRequest:
      type: object
      properties:
        granteeKind: {type: string, enum: [STAFF, EXTERNAL, CROSS_TENANT]}
YAML
}
run() { python3 "$CHECK" --names-file "$T/names.txt" "$T/$1" 2>&1; }
expect_pass() {
  out=$(run "$1"); rc=$?
  if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — '$1' rejected:"; echo "$out"; failed=1; else echo "gst-ci: self-test '$1' passed as expected"; fi
}
expect_fail() { # name, finding (whole line)
  out=$(run "$1"); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' was accepted"; failed=1
  elif printf '%s\n' "$out" | grep -Fxq -- "$2"; then echo "gst-ci: self-test '$1' failed as expected"
  else echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1; fi
}
P='audited-operations.tsv: '
mkroot pass "$BASE"; expect_pass pass
out=$(python3 "$CHECK" "$T/pass" 2>&1); [ $? -ne 0 ] && printf '%s\n' "$out" | grep -Fxq 'audited-operations.tsv: audited operation '"'"'acknowledgeMapping'"'"' has no line' \
  && echo "gst-ci: self-test built-in names failed as expected" || { echo "gst-ci: FAIL — the built-in 69 names did not reject the small root"; failed=1; }
# a case is the base table with one sed edit; the second argument is the sed script
mut() { n=$1; mkroot "$n" "$BASE"; sed -i "$2" "$T/$n/catalogue/audited-operations.tsv"; }
mut header '1s/listedName/listedname/';               expect_fail header "${P}the header differs from listedName, operationId, service, discriminator"
mut header-space '1s/listedName\t/listedName /';      expect_fail header-space "${P}the header differs from listedName, operationId, service, discriminator"
mut three-columns '2s/\t-$//';                        expect_fail three-columns "${P}line 2 has 3 columns, not four"
mut five-columns '2s/$/\tx/';                         expect_fail five-columns "${P}line 2 has 5 columns, not four"
mut empty-cell '2s/\tsvca/\t/';                       expect_fail empty-cell "${P}line 2 has an empty cell"
mut not-audited '2s/^alpha/zeta/';                    expect_fail not-audited "${P}line 2: listedName 'zeta' is not one of the audited operations"
mut lookalike-name 's/^inviteUser/inviteuser/';       expect_fail lookalike-name "${P}line 8: listedName 'inviteuser' is not one of the audited operations"
mut missing-name '/^epsilon/d';                       expect_fail missing-name "${P}audited operation 'epsilon' has no line"
mut repeated '$a alpha\talpha\tsvca\t-' ;             sed -i '$s/^ //' "$T/repeated/catalogue/audited-operations.tsv"; expect_fail repeated "${P}line 10 repeats line 2"
mut no-service '2s/svca/svcz/';                       expect_fail no-service "${P}line 2: service 'svcz' has no openapi.yaml"
mut path-service '2s/svca/..\/svca/';                 expect_fail path-service "${P}line 2: service '../svca' has no openapi.yaml"
mut other-service-only '2s/svca/svcb/';               expect_fail other-service-only "${P}line 2: operationId 'alpha' is not an operationId of svcb/openapi.yaml"
mut unknown-id '2s/\talpha\tsvca/\talphaX\tsvca/';    expect_fail unknown-id "${P}line 2: operationId 'alphaX' is not an operationId of svca/openapi.yaml"
mut trailing-space '2s/\tsvca/ \tsvca/';              expect_fail trailing-space "${P}line 2: operationId 'alpha ' is not an operationId of svca/openapi.yaml"
mkroot uncontracted-but "$BASE"; printf '  /g:\n    post: {operationId: gamma}\n' >> "$T/uncontracted-but/svcb/openapi.yaml"
expect_fail uncontracted-but "${P}line 4: UNCONTRACTED, but svcb/openapi.yaml has the operationId 'gamma'"
# a new service that repeats a listed id fails until the table has its line
mkroot repeated-id "$BASE"; mkdir -p "$T/repeated-id/svcc"; cp "$T/repeated-id/svcb/openapi.yaml" "$T/repeated-id/svcc/openapi.yaml"
expect_fail repeated-id "${P}audited operation 'beta' is an operationId of svcc/openapi.yaml and has no line for it"
mut disc-elsewhere '2s/\t-$/\tSTAFF/';                 expect_fail disc-elsewhere "${P}line 2: discriminator 'STAFF' is allowed only on operationId grantRole of access"
mut disc-lookalike-id 's/\tgrantRole\taccess\tSTAFF/\tgrantRoles\taccess\tSTAFF/'; expect_fail disc-lookalike-id "${P}line 8: discriminator 'STAFF' is allowed only on operationId grantRole of access"
mut disc-lookalike-service 's/\tgrantRole\taccess\tSTAFF/\tgrantRole\tsvca\tSTAFF/'; expect_fail disc-lookalike-service "${P}line 8: discriminator 'STAFF' is allowed only on operationId grantRole of access"
mut disc-case 's/\tSTAFF$/\tStaff/';                   expect_fail disc-case "${P}line 8: discriminator 'Staff' is not a member of GrantRequest.granteeKind"
mut disc-plural 's/\tSTAFF$/\tSTAFFS/';                expect_fail disc-plural "${P}line 8: discriminator 'STAFFS' is not a member of GrantRequest.granteeKind"
mut invite-user-kind 's/\tSTAFF$/\tEXTERNAL/';        expect_fail invite-user-kind "${P}line 8: the discriminator of inviteUser must be STAFF"
mut invite-user-plain '8s/\tSTAFF$/\t-/';             expect_fail invite-user-plain "${P}line 8: the discriminator of inviteUser must be STAFF"
mut invite-external-kind 's/\tEXTERNAL$/\tCROSS_TENANT/'; expect_fail invite-external-kind "${P}line 9: the discriminator of inviteExternalIdentity must be EXTERNAL"
mut grant-plain-twice '$a grantRole\tgrantRole\taccess\t-'; sed -i '$s/^ //' "$T/grant-plain-twice/catalogue/audited-operations.tsv"; expect_fail grant-plain-twice "${P}grantRole must have exactly one line with discriminator -"
mut grant-plain-none '7s/\t-$/\tCROSS_TENANT/';       expect_fail grant-plain-none "${P}grantRole must have exactly one line with discriminator -"
# table and file faults
mkroot no-table "$BASE"; rm "$T/no-table/catalogue/audited-operations.tsv"; expect_fail no-table "${P}cannot be read: FileNotFoundError"
mkroot empty-table ""; expect_fail empty-table "${P}is empty"
mkroot bad-yaml "$BASE"; printf 'paths: [unclosed\n' > "$T/bad-yaml/svcb/openapi.yaml"
out=$(run bad-yaml); [ $? -ne 0 ] && printf '%s\n' "$out" | grep -q "^${P}svcb/openapi.yaml cannot be parsed" && echo "gst-ci: self-test 'bad-yaml' failed as expected" || { echo "gst-ci: FAIL — 'bad-yaml':"; echo "$out"; failed=1; }
mkroot no-enum "$BASE"
sed -i 's/granteeKind: {type: string, enum: \[STAFF, EXTERNAL, CROSS_TENANT\]}/granteeKind: {type: string}/' "$T/no-enum/access/openapi.yaml"
expect_fail no-enum "${P}access/openapi.yaml has no GrantRequest.granteeKind enum"
[ "$failed" -eq 0 ] && echo "gst-ci: check-audited-operations.py self-test passed."
exit $failed
