#!/usr/bin/env sh
# spectral/self-test-required-identifiers.sh — INV-T-4909 (lesson P20): proves
# check-required-identifiers.py fires. One passing fixture and one fixture for each failure:
# missing identifier, substring only, other case, missing file, unknown kind, missing column,
# empty table, no table at all, identifier present only in another service's directory.
set -u
cd "$(dirname "$0")"
F=fixtures/required-identifiers
failed=0
out=$(python3 check-required-identifiers.py $F/pass 2>&1) || { echo "gst-ci: FAIL — compliant fixture rejected:"; echo "$out"; failed=1; }
expect_fail() {
  out=$(python3 check-required-identifiers.py "$F/$1" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail missing-identifier "not found as a property"
expect_fail substring-only "not found as a property"
expect_fail other-case "not found as a property"
expect_fail missing-file "does not exist"
expect_fail unknown-kind "unknown kind"
expect_fail missing-column "four non-empty columns"
expect_fail empty-table "the table has no rows"
expect_fail no-table "no */required-identifiers.tsv found"
expect_fail other-service-only "not found as a property"
for c in query-param-missing query-param-wrong-location query-param-other-case query-param-property-only; do
  expect_fail $c "not found as a query_parameter"   # INV-T-5959
done
# INV-T-6049 (INV-Q-6105, INV-Q-6156): the schema-scoped kinds. Each case is generated in a temporary
# directory and removed at the end of the script.
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mk() { # name, kind, identifier -> $T/<name>/svc with the shared openapi.yaml and a one-row table
  mkdir -p "$T/$1/svc"
  cat > "$T/$1/svc/openapi.yaml" <<'YAML'
openapi: 3.1.0
info: {title: fixture, version: 1.0.0}
paths:
  /x:
    post:
      operationId: doThing
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                field: {type: string, enum: [INLINE]}
components:
  schemas:
    Thing:
      type: object
      properties:
        status:
          type: string
          enum: [ACTIVE, CLOSED]
        color:
          type: string
          enum: [RED]
        wrap:
          type: object
          properties:
            inner: {type: string, enum: [NESTED]}
      allOf:
        - properties:
            viaAllOf: {type: string, enum: [VIA]}
    Other:
      type: object
      properties:
        status:
          type: string
          enum: [ACTIVE, OPEN]
        extra: {type: string}
YAML
  printf 'identifier\tkind\tfile\trequirement\n%s\t%s\topenapi.yaml\tR-6\n' "$3" "$2" > "$T/$1/svc/required-identifiers.tsv"
}
scoped_fail() { # name, kind, identifier, expected text
  mk "$1" "$2" "$3"
  expect_fail_dir "$T/$1" "$4"
}
expect_fail_dir() {
  out=$(python3 check-required-identifiers.py "$1" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*"R-6"*|*"R-6"*"$2"*) echo "gst-ci: self-test '${1##*/}' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2' naming R-6:"; echo "$out"; failed=1;; esac; fi
}
NF="not found as a"
scoped_fail enum-only-second-schema schema_enum_value Thing.status.OPEN "$NF schema_enum_value"
scoped_fail enum-other-property schema_enum_value Thing.status.RED "$NF schema_enum_value"
scoped_fail enum-suppliers schema_enum_value Thing.status.ACTIVES "$NF schema_enum_value"
scoped_fail enum-lower-case schema_enum_value Thing.status.active "$NF schema_enum_value"
scoped_fail enum-missing-member schema_enum_value Thing.status.NOPE "$NF schema_enum_value"
scoped_fail enum-nested-property schema_enum_value Thing.inner.NESTED "$NF schema_enum_value"
scoped_fail enum-allof-property schema_enum_value Thing.viaAllOf.VIA "$NF schema_enum_value"
scoped_fail enum-lookalike-schema schema_enum_value thing.status.ACTIVE "$NF schema_enum_value"
scoped_fail enum-lookalike-schema-plural schema_enum_value Things.status.ACTIVE "$NF schema_enum_value"
scoped_fail enum-inline-schema schema_enum_value Inline.field.INLINE "$NF schema_enum_value"
scoped_fail enum-two-parts schema_enum_value Thing.status "needs 3 non-empty"
scoped_fail enum-empty-part schema_enum_value Thing..ACTIVE "needs 3 non-empty"
scoped_fail prop-only-second-schema schema_property Thing.extra "$NF schema_property"
scoped_fail prop-nested schema_property Thing.inner "$NF schema_property"
scoped_fail prop-allof schema_property Thing.viaAllOf "$NF schema_property"
scoped_fail prop-lookalike-schema schema_property thing.status "$NF schema_property"
scoped_fail prop-lookalike-schema-plural schema_property Things.status "$NF schema_property"
scoped_fail prop-lookalike-property schema_property Thing.Status "$NF schema_property"
scoped_fail prop-inline-schema schema_property Inline.field "$NF schema_property"
scoped_fail prop-no-dot schema_property Thing "needs 2 non-empty"
scoped_fail prop-empty-part schema_property Thing. "needs 2 non-empty"
# the schema exists only in another service's directory
mk other-service-schema schema_property Elsewhere.status
mkdir -p "$T/other-service-schema/elsewhere"
sed 's/    Other:/    Elsewhere:/' "$T/other-service-schema/svc/openapi.yaml" > "$T/other-service-schema/elsewhere/openapi.yaml"
expect_fail_dir "$T/other-service-schema" "$NF schema_property"
# INV-T-6937 (INV-Q-7029 A): the kind operation_error_code. A fixture with operations that declare codes,
# a description that names another, and a second operation.
mke() { # name, identifier -> $T/<name>/svc
  mkdir -p "$T/$1/svc"
  cat > "$T/$1/svc/openapi.yaml" <<'YAML'
openapi: 3.1.0
info: {title: fixture, version: 1.0.0}
paths:
  /x:
    post:
      operationId: recordDocument
      description: Held with PER-009 when no period covers it.
      x-error-codes: [ING-002, PER-011]
  /y:
    get:
      operationId: listThings
      x-error-codes: [PER-009]
YAML
  printf 'identifier\tkind\tfile\trequirement\n%s\toperation_error_code\topenapi.yaml\tR-6\n' "$2" > "$T/$1/svc/required-identifiers.tsv"
}
mke code-pass recordDocument.PER-011
out=$(python3 check-required-identifiers.py "$T/code-pass" 2>&1) || { echo "gst-ci: FAIL — operation_error_code pass fixture rejected:"; echo "$out"; failed=1; }
code_fail() { mke "$1" "$2"; expect_fail_dir "$T/$1" "$3"; }
code_fail code-removed recordDocument.PER-012 "$NF operation_error_code"
code_fail code-description-only recordDocument.PER-009 "$NF operation_error_code"
code_fail code-near-miss recordDocument.PER-009x "$NF operation_error_code"
code_fail code-declared-by-other listThings.PER-011 "$NF operation_error_code"
code_fail code-lower-case recordDocument.per-011 "$NF operation_error_code"
code_fail code-lookalike-operation recordDocuments.PER-011 "$NF operation_error_code"
code_fail code-no-dot recordDocument "needs 2 non-empty"
mke code-other-service recordDocument.PER-011
mkdir -p "$T/code-other-service/elsewhere"
mv "$T/code-other-service/svc/openapi.yaml" "$T/code-other-service/elsewhere/openapi.yaml"
cat > "$T/code-other-service/svc/openapi.yaml" <<'YAML'
openapi: 3.1.0
info: {title: fixture, version: 1.0.0}
paths: {}
YAML
expect_fail_dir "$T/code-other-service" "$NF operation_error_code"
# INV-T-6937 (INV-Q-7027 A): --base. Each case is a throwaway git repository with a base commit.
TSV='identifier\tkind\tfile\trequirement\nalpha\tproperty\topenapi.yaml\tR-6\nbeta\tproperty\topenapi.yaml\tR-6\n'
mkb() { # name -> $T/<name> with one commit holding svc/openapi.yaml and the two-row table
  mkdir -p "$T/$1/svc"
  printf 'openapi: 3.1.0\ninfo: {title: f, version: 1.0.0}\npaths:\n  /x:\n    get:\n      operationId: op\n      responses:\n        "200":\n          content:\n            application/json:\n              schema:\n                type: object\n                properties:\n                  alpha: {type: string}\n                  beta: {type: string}\n                  gamma: {type: string}\n' > "$T/$1/svc/openapi.yaml"
  printf "$TSV" > "$T/$1/svc/required-identifiers.tsv"
  git -C "$T/$1" init -q && git -C "$T/$1" add -A && git -C "$T/$1" -c user.name=t -c user.email=t@t commit -q -m base
}
base_case() { # name, expected rc (0 pass, 1 fail), expected text, then the mutation as a command run in $T/<name>
  n=$1; want=$2; txt=$3; mkb "$n"; shift 3; (cd "$T/$n" && eval "$*")
  out=$(python3 check-required-identifiers.py --base "$(git -C "$T/$n" rev-parse HEAD)" "$T/$n" 2>&1); rc=$?
  if [ "$want" = 0 ] && [ $rc -ne 0 ]; then echo "gst-ci: FAIL — base case '$n' rejected:"; echo "$out"; failed=1
  elif [ "$want" = 1 ] && [ $rc -eq 0 ]; then echo "gst-ci: FAIL — base case '$n' was accepted"; failed=1
  elif [ "$want" = 1 ] && ! case "$out" in *"$txt"*) true;; *) false;; esac; then echo "gst-ci: FAIL — base case '$n' failed, but not with '$txt':"; echo "$out"; failed=1
  else echo "gst-ci: self-test base '$n' behaved as expected"; fi
}
base_case base-row-deleted 1 "'beta' (property, openapi.yaml)" "sed -i '/^beta/d' svc/required-identifiers.tsv"
base_case base-row-added 0 "" "printf 'gamma\tproperty\topenapi.yaml\tR-6\n' >> svc/required-identifiers.tsv"
base_case base-requirement-changed 0 "" "sed -i 's/^beta\(.*\)R-6/beta\1R-7/' svc/required-identifiers.tsv"
base_case base-file-changed 1 "'beta' (property, openapi.yaml)" "sed -i 's/^beta\tproperty\topenapi.yaml/beta\tproperty\topenapi.yml/' svc/required-identifiers.tsv; cp svc/openapi.yaml svc/openapi.yml"
base_case base-kind-changed 1 "'beta' (property, openapi.yaml)" "sed -i 's/^beta\tproperty/beta\toperation/' svc/required-identifiers.tsv"
base_case base-table-deleted 1 "svc/required-identifiers.tsv: exists at" "rm svc/required-identifiers.tsv; mkdir -p other; cp svc/openapi.yaml other/; printf 'identifier\tkind\tfile\trequirement\nalpha\tproperty\topenapi.yaml\tR-6\n' > other/required-identifiers.tsv"
base_case base-comment-deleted 0 "" "printf '# a comment\n' >> svc/required-identifiers.tsv; git add -A; git -c user.name=t -c user.email=t@t commit -q -m c; sed -i '/^# a comment/d' svc/required-identifiers.tsv"
mkb base-bad-sha
for bad in "" "0000000000000000000000000000000000000000" "--output=x"; do
  out=$(python3 check-required-identifiers.py --base "$bad" "$T/base-bad-sha" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — --base '$bad' was accepted"; failed=1; else echo "gst-ci: self-test base sha '$bad' failed as expected"; fi
done
[ "$failed" -eq 0 ] && echo "gst-ci: check-required-identifiers.py self-test passed."
exit $failed
