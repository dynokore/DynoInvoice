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
# INV-T-11736 (INV-Q-11733 A): the kind operation_response. Operation postThing answers 202 and 403 (one as the
# integer key 202), listThings answers 200 and 409, and components.responses holds 418.
mkr() { # name, identifier -> $T/<name>/svc
  mkdir -p "$T/$1/svc"
  cat > "$T/$1/svc/openapi.yaml" <<'YAML'
openapi: 3.1.0
info: {title: fixture, version: 1.0.0}
paths:
  /x:
    post:
      operationId: postThing
      description: Answers 500 when broken.
      responses:
        202: {description: accepted}
        '403': {description: forbidden}
        default: {description: other}
  /y:
    get:
      operationId: listThings
      responses:
        '200': {description: ok}
        '409': {description: conflict}
components:
  responses:
    Teapot: {description: teapot}
    '418': {description: teapot}
YAML
  printf 'identifier\tkind\tfile\trequirement\n%s\toperation_response\topenapi.yaml\tR-6\n' "$2" > "$T/$1/svc/required-identifiers.tsv"
}
for ok in postThing.202 postThing.403 postThing.default listThings.409; do
  mkr "resp-pass-$ok" "$ok"
  out=$(python3 check-required-identifiers.py "$T/resp-pass-$ok" 2>&1) || { echo "gst-ci: FAIL — operation_response '$ok' rejected:"; echo "$out"; failed=1; }
done
resp_fail() { mkr "$1" "$2"; expect_fail_dir "$T/$1" "$3"; }
resp_fail resp-absent postThing.200 "$NF operation_response"
resp_fail resp-other-operation postThing.409 "$NF operation_response"
resp_fail resp-components-only postThing.418 "$NF operation_response"
resp_fail resp-substring-long postThing.2020 "$NF operation_response"
resp_fail resp-substring-short postThing.20 "$NF operation_response"
resp_fail resp-operation-case postthing.202 "$NF operation_response"
resp_fail resp-unknown-operation postThings.202 "$NF operation_response"
resp_fail resp-no-dot postThing "needs 2 non-empty dot-separated parts (<operationId>.<STATUS>)"
resp_fail resp-empty-status postThing. "needs 2 non-empty dot-separated parts (<operationId>.<STATUS>)"
# INV-T-18374: the kinds asyncapi_message and asyncapi_operation. The fixture document has the message LinkIssued
# (components.messages), the channel key chanOnly (a channels key only), the operation key sendLink whose description writes
# the operationId-style value descOnlyOp, and an operationId-style field opIdOnly. Another service holds only the look-alike key.
mka() { # name, identifier, kind, file -> $T/<name>/svc and $T/<name>/other
  mkdir -p "$T/$1/svc" "$T/$1/other"
  cat > "$T/$1/svc/asyncapi.yaml" <<'YAML'
asyncapi: 3.0.0
info: {title: fixture, version: 1.0.0}
channels:
  chanOnly:
    address: 'x.y.v1'
    messages:
      LinkIssued:
        $ref: '#/components/messages/LinkIssued'
operations:
  sendLink:
    action: send
    description: Calls descOnlyOp and not more.
    operationId: opIdOnly
    channel:
      $ref: '#/channels/chanOnly'
components:
  messages:
    LinkIssued:
      name: com.example.LinkIssuedName.v1
      title: LinkIssuedTitle
YAML
  printf 'asyncapi: 3.0.0\ninfo: {title: other, version: 1.0.0}\ncomponents:\n  messages:\n    OnlyOther: {name: x}\noperations:\n  onlyOtherOp: {action: send}\n' > "$T/$1/other/asyncapi.yaml"
  printf 'identifier\tkind\tfile\trequirement\n%s\t%s\t%s\tR-6\n' "$2" "$3" "$4" > "$T/$1/svc/required-identifiers.tsv"
  printf 'identifier\tkind\tfile\trequirement\nx\tproperty\topenapi.yaml\tR-6\n' > "$T/$1/other/required-identifiers.tsv"
  printf 'openapi: 3.1.0\ninfo: {title: o, version: 1.0.0}\nx: {properties: {x: {type: string}}}\n' > "$T/$1/other/openapi.yaml"
}
for ok in "LinkIssued asyncapi_message" "sendLink asyncapi_operation"; do
  set -- $ok; mka "am-pass-$1" "$1" "$2" asyncapi.yaml
  out=$(python3 check-required-identifiers.py "$T/am-pass-$1" 2>&1) || { echo "gst-ci: FAIL — INV-T-18374 '$1' ($2) rejected:"; echo "$out"; failed=1; }
done
am_fail() { # name, identifier, kind, file, expected text
  mka "$1" "$2" "$3" "$4"; expect_fail_dir "$T/$1" "$5"
}
am_fail am-msg-absent Nope asyncapi_message asyncapi.yaml "$NF asyncapi_message"
am_fail am-msg-case linkIssued asyncapi_message asyncapi.yaml "$NF asyncapi_message"
am_fail am-msg-substring Link asyncapi_message asyncapi.yaml "$NF asyncapi_message"
am_fail am-msg-channel-only chanOnly asyncapi_message asyncapi.yaml "$NF asyncapi_message"
am_fail am-msg-name-value LinkIssuedName asyncapi_message asyncapi.yaml "$NF asyncapi_message"
am_fail am-msg-operation-key sendLink asyncapi_message asyncapi.yaml "$NF asyncapi_message"
am_fail am-op-absent nope asyncapi_operation asyncapi.yaml "$NF asyncapi_operation"
am_fail am-op-case SendLink asyncapi_operation asyncapi.yaml "$NF asyncapi_operation"
am_fail am-op-substring send asyncapi_operation asyncapi.yaml "$NF asyncapi_operation"
am_fail am-op-description-only descOnlyOp asyncapi_operation asyncapi.yaml "$NF asyncapi_operation"
am_fail am-op-operationid-value opIdOnly asyncapi_operation asyncapi.yaml "$NF asyncapi_operation"
am_fail am-op-message-key LinkIssued asyncapi_operation asyncapi.yaml "$NF asyncapi_operation"
am_fail am-op-channel-key chanOnly asyncapi_operation asyncapi.yaml "$NF asyncapi_operation"
am_fail am-msg-leaves-dir LinkIssued asyncapi_message ../other/asyncapi.yaml "file leaves the directory"
am_fail am-op-leaves-dir sendLink asyncapi_operation ../other/asyncapi.yaml "file leaves the directory"
am_fail am-msg-other-service OnlyOther asyncapi_message asyncapi.yaml "$NF asyncapi_message"
am_fail am-op-other-service onlyOtherOp asyncapi_operation asyncapi.yaml "$NF asyncapi_operation"
# the kind operation is unchanged: an AsyncAPI operation key as an `operation` row stays a finding
am_fail am-operation-kind-unchanged sendLink operation asyncapi.yaml "$NF operation"
am_fail am-operation-kind-operationid opIdOnly asyncapi_operation asyncapi.yaml "$NF asyncapi_operation"
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
base_case base-requirement-changed 1 "requirement cell changed since" "sed -i 's/^beta\(.*\)R-6/beta\1R-7/' svc/required-identifiers.tsv"
base_case base-file-changed 1 "'beta' (property, openapi.yaml)" "sed -i 's/^beta\tproperty\topenapi.yaml/beta\tproperty\topenapi.yml/' svc/required-identifiers.tsv; cp svc/openapi.yaml svc/openapi.yml"
base_case base-kind-changed 1 "'beta' (property, openapi.yaml)" "sed -i 's/^beta\tproperty/beta\toperation/' svc/required-identifiers.tsv"
base_case base-table-deleted 1 "svc/required-identifiers.tsv: exists at" "rm svc/required-identifiers.tsv; mkdir -p other; cp svc/openapi.yaml other/; printf 'identifier\tkind\tfile\trequirement\nalpha\tproperty\topenapi.yaml\tR-6\n' > other/required-identifiers.tsv"
base_case base-comment-deleted 0 "" "printf '# a comment\n' >> svc/required-identifiers.tsv; git add -A; git -c user.name=t -c user.email=t@t commit -q -m c; sed -i '/^# a comment/d' svc/required-identifiers.tsv"
mkb base-bad-sha
for bad in "" "0000000000000000000000000000000000000000" "--output=x"; do
  out=$(python3 check-required-identifiers.py --base "$bad" "$T/base-bad-sha" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — --base '$bad' was accepted"; failed=1; else echo "gst-ci: self-test base sha '$bad' failed as expected"; fi
done
# INV-T-7643 (INV-Q-7641 A): the audited-operation completeness rule, with and without --base (INV-T-7650: unconditional, no baseline). Each case is a throwaway
# git repository whose base commit holds service svc with openapi.yaml, a one-row table and the audited table.
AUD=catalogue/audited-operations.tsv
mka() { # name -> $T/<name>; base audited line: op1 UNCONTRACTED svc -, plus the baseline line op2 op2 svc -
  mkdir -p "$T/$1/svc" "$T/$1/svc2" "$T/$1/catalogue"
  printf 'openapi: 3.1.0\ninfo: {title: f, version: 1.0.0}\npaths:\n  /a: {get: {operationId: op1}}\n  /b: {get: {operationId: op1x}}\n  /c: {get: {operationId: Op1}}\n  /e: {get: {operationId: op2}}\n  /d:\n    get:\n      responses:\n        "200":\n          content:\n            application/json:\n              schema: {type: object, properties: {alpha: {type: string}, op1: {type: string}}}\n' > "$T/$1/svc/openapi.yaml"
  cp "$T/$1/svc/openapi.yaml" "$T/$1/svc2/openapi.yaml"; cp "$T/$1/svc/openapi.yaml" "$T/$1/svc/asyncapi.yaml"
  printf 'identifier\tkind\tfile\trequirement\nalpha\tproperty\topenapi.yaml\tR-6\n' > "$T/$1/svc/required-identifiers.tsv"
  printf 'identifier\tkind\tfile\trequirement\nalpha\tproperty\topenapi.yaml\tR-6\n' > "$T/$1/svc2/required-identifiers.tsv"
  printf 'listedName\toperationId\tservice\tdiscriminator\nop1\tUNCONTRACTED\tsvc\t-\nop2\top2\tsvc\t-\n' > "$T/$1/$AUD"
  git -C "$T/$1" init -q && git -C "$T/$1" add -A && git -C "$T/$1" -c user.name=t -c user.email=t@t commit -q -m base
}
aud_case() { # name, want rc, expected text, mutation run in $T/<name> ("-" as name suffix: base without audited table when $5 set)
  n=$1; want=$2; txt=$3; mka "$n"; shift 3; (cd "$T/$n" && eval "$*")
  out=$(python3 check-required-identifiers.py --base "$(git -C "$T/$n" rev-parse HEAD)" "$T/$n" 2>&1); rc=$?
  if [ "$want" = 0 ] && [ $rc -ne 0 ]; then echo "gst-ci: FAIL — audited case '$n' rejected:"; echo "$out"; failed=1
  elif [ "$want" = 1 ] && [ $rc -eq 0 ]; then echo "gst-ci: FAIL — audited case '$n' was accepted"; failed=1
  elif [ "$want" = 1 ] && ! case "$out" in *"$txt"*) true;; *) false;; esac; then echo "gst-ci: FAIL — audited case '$n' failed, but not with '$txt':"; echo "$out"; failed=1
  else echo "gst-ci: self-test audited '$n' behaved as expected"; fi
}
HEADL='listedName\toperationId\tservice\tdiscriminator\n'
SETL="printf '${HEADL}op1\\top1\\tsvc\\t-\\nop2\\top2\\tsvc\\t-\\n' > $AUD"
ROW() { echo "printf '$1\\t$2\\t$3\\tR\\n' >> $4/required-identifiers.tsv"; }
FIND1="audited operation 'op1' of service 'svc' has no row (op1, operation, openapi.yaml) in svc/required-identifiers.tsv"
aud_case aud-ok 0 "" "$SETL; $(ROW op1 operation openapi.yaml svc); $(ROW op2 operation openapi.yaml svc)"
aud_case aud-no-row 1 "$FIND1" "$SETL"
aud_case aud-kind-property 1 "$FIND1" "$SETL; $(ROW op1 property openapi.yaml svc)"
aud_case aud-lookalike 1 "$FIND1" "$SETL; $(ROW op1x operation openapi.yaml svc)"
aud_case aud-case 1 "$FIND1" "$SETL; $(ROW Op1 operation openapi.yaml svc)"
aud_case aud-file 1 "$FIND1" "$SETL; $(ROW op1 operation asyncapi.yaml svc)"
aud_case aud-other-service 1 "$FIND1" "$SETL; $(ROW op1 operation openapi.yaml svc2)"
aud_case aud-no-table 1 "$FIND1; svc/required-identifiers.tsv does not exist" "$SETL; rm svc/required-identifiers.tsv"
aud_case aud-baseline 1 "audited operation 'op2' of service 'svc' has no row" "$SETL; $(ROW op1 operation openapi.yaml svc)"
aud_case aud-renamed 1 "audited operation 'op3' of service 'svc' has no row" "printf '${HEADL}op1\\tUNCONTRACTED\\tsvc\\t-\\nop3\\top3\\tsvc\\t-\\n' > $AUD"
aud_case aud-other-svc-line 1 "audited operation 'op1' of service 'svc2' has no row" "printf '${HEADL}op1\\top1\\tsvc2\\t-\\nop2\\top2\\tsvc\\t-\\n' > $AUD; $(ROW op1 operation openapi.yaml svc)"
aud_case aud-uncontracted 0 "" "$(ROW op2 operation openapi.yaml svc)"
aud_case aud-comment-row 1 "$FIND1" "$SETL; printf '# op1\\toperation\\topenapi.yaml\\tR\\n' >> svc/required-identifiers.tsv"
aud_case aud-deleted 1 "$AUD: exists at" "rm $AUD"
aud_case aud-traversal 1 "service '../svc'" "printf '${HEADL}op9\\top9\\t../svc\\t-\\n' > $AUD"
# the audited table absent at both the base and the head
mka aud-absent-both; git -C "$T/aud-absent-both" rm -q $AUD; git -C "$T/aud-absent-both" -c user.name=t -c user.email=t@t commit -q -m nobase
out=$(python3 check-required-identifiers.py --base "$(git -C "$T/aud-absent-both" rev-parse HEAD)" "$T/aud-absent-both" 2>&1) && echo "gst-ci: self-test audited 'aud-absent-both' behaved as expected" || { echo "gst-ci: FAIL — audited table absent at both was rejected:"; echo "$out"; failed=1; }
# absent at the base, present at the head with a contracted line and no row
mka aud-new-table; git -C "$T/aud-new-table" rm -q $AUD; git -C "$T/aud-new-table" -c user.name=t -c user.email=t@t commit -q -m nobase
B=$(git -C "$T/aud-new-table" rev-parse HEAD); mkdir -p "$T/aud-new-table/catalogue"; printf "${HEADL}op1\top1\tsvc\t-\n" > "$T/aud-new-table/$AUD"
out=$(python3 check-required-identifiers.py --base "$B" "$T/aud-new-table" 2>&1); rc=$?
case "$out" in *"$FIND1"*) [ $rc -eq 1 ] && echo "gst-ci: self-test audited 'aud-new-table' failed as expected" || { echo "gst-ci: FAIL — aud-new-table rc $rc"; failed=1; };; *) echo "gst-ci: FAIL — aud-new-table without the finding:"; echo "$out"; failed=1;; esac
# INV-T-7650: the rule is unconditional; without --base it is applied and a missing row exits 1
mka aud-plain; printf "${HEADL}op1\top1\tsvc\t-\n" > "$T/aud-plain/$AUD"
out=$(python3 check-required-identifiers.py "$T/aud-plain" 2>&1); rc=$?
case "$out" in *"$FIND1"*) [ $rc -eq 1 ] && echo "gst-ci: self-test audited 'aud-plain' (no --base) failed as expected" || { echo "gst-ci: FAIL — aud-plain rc $rc"; failed=1; };; *) echo "gst-ci: FAIL — aud-plain without the finding:"; echo "$out"; failed=1;; esac
# INV-T-13510 (INV-Q-11109 A, INV-AC-13509): the rename map. Each case is a throwaway git repository whose base commit holds
# svc/openapi.yaml, the rows alpha, beta, gamma and the map; the mutation runs before the check with --base.
RH='table\told_identifier\told_kind\told_file\tnew_identifier\tnew_kind\tnew_file\truling\n'
RL='svc/required-identifiers.tsv\tbeta\tproperty\topenapi.yaml\tdelta\tproperty\topenapi.yaml\tINV-Q-10647\n'
mkm() { # name, map lines (printf format) -> $T/<name>; the map is committed at the base
  mkdir -p "$T/$1/svc" "$T/$1/spectral"
  printf 'openapi: 3.1.0\ninfo: {title: f, version: 1.0.0}\npaths:\n  /x:\n    get:\n      operationId: op\n      responses:\n        "200":\n          content:\n            application/json:\n              schema:\n                type: object\n                properties:\n                  alpha: {type: string}\n                  beta: {type: string}\n                  gamma: {type: string}\n' > "$T/$1/svc/openapi.yaml"
  printf 'identifier\tkind\tfile\trequirement\nalpha\tproperty\topenapi.yaml\tR-6\nbeta\tproperty\topenapi.yaml\tR-6\ngamma\tproperty\topenapi.yaml\tR-6\n' > "$T/$1/svc/required-identifiers.tsv"
  [ -n "$2" ] && printf "${RH}$2" > "$T/$1/spectral/required-identifiers-renames.tsv"
  git -C "$T/$1" init -q && git -C "$T/$1" add -A && git -C "$T/$1" -c user.name=t -c user.email=t@t commit -q -m base
}
rename_case() { # name, expected rc, expected text, map lines at the base, mutation run in $T/<name>
  n=$1; want=$2; txt=$3; ml=$4; mkm "$n" "$ml"; shift 4; (cd "$T/$n" && eval "$*")
  out=$(python3 check-required-identifiers.py --base "$(git -C "$T/$n" rev-parse HEAD)" "$T/$n" 2>&1); rc=$?
  if [ "$want" = 0 ] && [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-13509 rename case '$n' rejected:"; echo "$out"; failed=1
  elif [ "$want" = 1 ] && [ $rc -eq 0 ]; then echo "gst-ci: FAIL — INV-AC-13509 rename case '$n' was accepted"; failed=1
  elif [ "$want" = 1 ] && ! case "$out" in *"$txt"*) true;; *) false;; esac; then echo "gst-ci: FAIL — INV-AC-13509 rename case '$n' failed, but not with '$txt':"; echo "$out"; failed=1
  else echo "gst-ci: self-test INV-AC-13509 rename '$n' behaved as expected"; fi
}
SWAP="sed -i 's/^beta\tproperty\topenapi.yaml\tR-6/delta\tproperty\topenapi.yaml\tR-6/' svc/required-identifiers.tsv; sed -i '/^ *beta: /a\                  delta: {type: string}' svc/openapi.yaml"
GONE="'beta' (property, openapi.yaml)"
rename_case rename-mapped 0 "" "$RL" "$SWAP"
rename_case rename-map-added-in-change 1 "row removed since" "" "$SWAP; printf \"\$(printf '$RH$RL')\" > spectral/required-identifiers-renames.tsv"
rename_case rename-unmapped-delete 1 "$GONE" "$RL" "sed -i '/^beta/d' svc/required-identifiers.tsv"
rename_case rename-lookalike-old 1 "$GONE" 'svc/required-identifiers.tsv\tbetax\tproperty\topenapi.yaml\tdelta\tproperty\topenapi.yaml\tINV-Q-10647\n' "$SWAP"
rename_case rename-kind-differs 1 "$GONE" "$RL" "$SWAP; sed -i 's/^delta\tproperty/delta\toperation/' svc/required-identifiers.tsv"
rename_case rename-file-differs 1 "$GONE" "$RL" "$SWAP; sed -i 's/^delta\tproperty\topenapi.yaml/delta\tproperty\topenapi.yml/' svc/required-identifiers.tsv; cp svc/openapi.yaml svc/openapi.yml"
rename_case rename-other-table 1 "$GONE" 'other/required-identifiers.tsv\tbeta\tproperty\topenapi.yaml\tdelta\tproperty\topenapi.yaml\tINV-Q-10647\n' "$SWAP"
rename_case rename-requirement-differs 1 "$GONE" "$RL" "$SWAP; sed -i 's/^delta\(.*\)R-6/delta\1R-7/' svc/required-identifiers.tsv"
rename_case rename-new-property-missing 1 "not found as a property" "$RL" "sed -i 's/^beta\tproperty\topenapi.yaml/delta\tproperty\topenapi.yaml/' svc/required-identifiers.tsv"
rename_case map-malformed 1 "map line 2" 'svc/required-identifiers.tsv\tbeta\tproperty\topenapi.yaml\tdelta\tproperty\tINV-Q-10647\n' "true"
rename_case map-bad-ruling 1 "map line 2" 'svc/required-identifiers.tsv\tbeta\tproperty\topenapi.yaml\tdelta\tproperty\topenapi.yaml\tnone\n' "true"
rename_case map-duplicate-old 1 "map line 3" "$RL$RL" "true"
rename_case map-self 1 "map line 2" 'svc/required-identifiers.tsv\tbeta\tproperty\topenapi.yaml\tbeta\tproperty\topenapi.yaml\tINV-Q-10647\n' "true"
rename_case map-unused-line 0 "" "$RL" "true"
# INV-T-17435 (INV-Q-17427 A, INV-AC-17436): the retirement line type of the rename map. Fixtures live in fixtures/required-identifiers-retire/;
# each case is a throwaway git repository whose base commit holds svc with the base table, the base openapi.yaml, asyncapi.yaml and the map.
RT=fixtures/required-identifiers-retire
RR='svc/required-identifiers.tsv\tissueSignatoryLink\toperation\topenapi.yaml\tRETIRED\tRETIRED\tRETIRED\tINV-Q-17427\n'
mkt() { # name, map lines -> $T/<name>
  mkdir -p "$T/$1/svc" "$T/$1/spectral" "$T/$1/other"
  cp $RT/openapi-base.yaml "$T/$1/svc/openapi.yaml"; cp $RT/asyncapi.yaml "$T/$1/svc/asyncapi.yaml"; cp $RT/table-base.tsv "$T/$1/svc/required-identifiers.tsv"
  cp $RT/openapi-base.yaml "$T/$1/other/openapi.yaml"; cp $RT/table-base.tsv "$T/$1/other/required-identifiers.tsv"
  printf "${RH}$2" > "$T/$1/spectral/required-identifiers-renames.tsv"
  git -C "$T/$1" init -q && git -C "$T/$1" add -A && git -C "$T/$1" -c user.name=t -c user.email=t@t commit -q -m base
}
retire_case() { # name, expected rc, expected text, map lines at the base, mutation run in $T/<name>
  n=$1; want=$2; txt=$3; ml=$4; mkt "$n" "$ml"; shift 4; (cd "$T/$n" && eval "$*")
  out=$(python3 check-required-identifiers.py --base "$(git -C "$T/$n" rev-parse HEAD)" "$T/$n" 2>&1); rc=$?
  if [ "$want" = 0 ] && [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-17436 retire case '$n' rejected:"; echo "$out"; failed=1
  elif [ "$want" = 1 ] && [ $rc -eq 0 ]; then echo "gst-ci: FAIL — INV-AC-17436 retire case '$n' was accepted"; failed=1
  elif [ "$want" = 1 ] && ! case "$out" in *"$txt"*) true;; *) false;; esac; then echo "gst-ci: FAIL — INV-AC-17436 retire case '$n' failed, but not with '$txt':"; echo "$out"; failed=1
  else echo "gst-ci: self-test INV-AC-17436 retire '$n' behaved as expected"; fi
}
HEADRM="cp \"$PWD/$RT/table-head.tsv\" svc/required-identifiers.tsv; cp \"$PWD/$RT/openapi-head.yaml\" svc/openapi.yaml"
GONE2="'issueSignatoryLink' (operation, openapi.yaml)"
retire_case retire-ok 0 "" "$RR" "$HEADRM"
retire_case retire-still-present 1 "$GONE2" "$RR" "cp \"$PWD/$RT/table-head.tsv\" svc/required-identifiers.tsv"
retire_case retire-lookalike-plural 1 "$GONE2" 'svc/required-identifiers.tsv\tissueSignatoryLinks\toperation\topenapi.yaml\tRETIRED\tRETIRED\tRETIRED\tINV-Q-17427\n' "$HEADRM"
retire_case retire-lookalike-case 1 "$GONE2" 'svc/required-identifiers.tsv\tissuesignatorylink\toperation\topenapi.yaml\tRETIRED\tRETIRED\tRETIRED\tINV-Q-17427\n' "$HEADRM"
retire_case retire-other-table 1 "$GONE2" 'other/required-identifiers.tsv\tissueSignatoryLink\toperation\topenapi.yaml\tRETIRED\tRETIRED\tRETIRED\tINV-Q-17427\n' "$HEADRM"
retire_case retire-kind-property 1 "$GONE2" 'svc/required-identifiers.tsv\tissueSignatoryLink\tproperty\topenapi.yaml\tRETIRED\tRETIRED\tRETIRED\tINV-Q-17427\n' "$HEADRM"
retire_case retire-file-asyncapi 1 "$GONE2" 'svc/required-identifiers.tsv\tissueSignatoryLink\toperation\tasyncapi.yaml\tRETIRED\tRETIRED\tRETIRED\tINV-Q-17427\n' "$HEADRM"
retire_case retire-ruling-req 1 "map line 2" 'svc/required-identifiers.tsv\tissueSignatoryLink\toperation\topenapi.yaml\tRETIRED\tRETIRED\tRETIRED\tINV-REQ-2588\n' "$HEADRM"
retire_case retire-ruling-req-row 1 "$GONE2" 'svc/required-identifiers.tsv\tissueSignatoryLink\toperation\topenapi.yaml\tRETIRED\tRETIRED\tRETIRED\tINV-REQ-2588\n' "$HEADRM"
retire_case retire-ruling-empty 1 "map line 2" 'svc/required-identifiers.tsv\tissueSignatoryLink\toperation\topenapi.yaml\tRETIRED\tRETIRED\tRETIRED\t\n' "$HEADRM"
retire_case retire-ruling-empty-row 1 "$GONE2" 'svc/required-identifiers.tsv\tissueSignatoryLink\toperation\topenapi.yaml\tRETIRED\tRETIRED\tRETIRED\t\n' "$HEADRM"
retire_case retire-duplicate 1 "map line 3" "$RR$RR" "$HEADRM"
retire_case retire-duplicate-row 1 "$GONE2" "$RR$RR" "$HEADRM"
retire_case retire-partial 1 "map line 2" 'svc/required-identifiers.tsv\tissueSignatoryLink\toperation\topenapi.yaml\tRETIRED\tproperty\topenapi.yaml\tINV-Q-17427\n' "$HEADRM"
retire_case retire-partial-row 1 "$GONE2" 'svc/required-identifiers.tsv\tissueSignatoryLink\toperation\topenapi.yaml\tRETIRED\tproperty\topenapi.yaml\tINV-Q-17427\n' "$HEADRM"
retire_case retire-map-added-in-change 1 "$GONE2" "" "$HEADRM; printf \"\$(printf '$RH$RR')\" > spectral/required-identifiers-renames.tsv"
retire_case retire-rename-still-passes 0 "" 'svc/required-identifiers.tsv\tissueSignatoryLink\toperation\topenapi.yaml\tkeepOp2\toperation\topenapi.yaml\tINV-Q-10647\n' "sed -i 's/^issueSignatoryLink\toperation/keepOp2\toperation/' svc/required-identifiers.tsv; sed -i 's/operationId: issueSignatoryLink/operationId: keepOp2/' svc/openapi.yaml"
# INV-T-13720 (INV-Q-13717 A): the kind root_property. Each case is a JSON Schema or OpenAPI file in the temporary directory.
mkr() { # name, kind, identifier, json text -> $T/<name>/svc/s.json with a one-row table
  mkdir -p "$T/$1/svc"; printf '%s\n' "$4" > "$T/$1/svc/s.json"
  printf 'identifier\tkind\tfile\trequirement\n%s\t%s\ts.json\tR-6\n' "$3" "$2" > "$T/$1/svc/required-identifiers.tsv"
}
RP='root_property'
ALLOF='{"type":"object","properties":{"other":{"type":"string"}},"allOf":[{"if":{"x":1},"then":{"properties":{"documentFunction":{"const":"INVOICE"}}}}]}'
mkr rp-pass $RP documentFunction '{"type":"object","properties":{"documentFunction":{"type":"string"}}}'
out=$(python3 check-required-identifiers.py "$T/rp-pass" 2>&1) || { echo "gst-ci: FAIL — root_property pass fixture rejected:"; echo "$out"; failed=1; }
rp_fail() { mkr "$1" "$2" "$3" "$4"; expect_fail_dir "$T/$1" "$5"; }
rp_fail rp-allof-only $RP documentFunction "$ALLOF" "$NF root_property"
rp_fail rp-nested $RP documentFunction '{"properties":{"a":{"type":"object","properties":{"documentFunction":{}}}}}' "$NF root_property"
rp_fail rp-defs $RP documentFunction '{"properties":{"a":{}},"$defs":{"D":{"properties":{"documentFunction":{}}}}}' "$NF root_property"
rp_fail rp-definitions $RP documentFunction '{"properties":{"a":{}},"definitions":{"D":{"properties":{"documentFunction":{}}}}}' "$NF root_property"
rp_fail rp-substring $RP documentFunction '{"properties":{"documentFunctionX":{}}}' "$NF root_property"
rp_fail rp-other-case $RP documentFunction '{"properties":{"DocumentFunction":{}}}' "$NF root_property"
rp_fail rp-openapi-components $RP documentFunction '{"openapi":"3.1.0","components":{"schemas":{"X":{"properties":{"documentFunction":{}}}}}}' "$NF root_property"
rp_fail rp-lookalike-kind root_properties documentFunction '{"properties":{"documentFunction":{}}}' "unknown kind"
mkr rp-other-service $RP documentFunction '{"properties":{"a":{}}}'
mkdir -p "$T/rp-other-service/elsewhere"; echo '{"properties":{"documentFunction":{}}}' > "$T/rp-other-service/elsewhere/s.json"
expect_fail_dir "$T/rp-other-service" "$NF root_property"
mkr rp-old-kind property documentFunction "$ALLOF"
out=$(python3 check-required-identifiers.py "$T/rp-old-kind" 2>&1) || { echo "gst-ci: FAIL — kind property on the allOf-only file was rejected:"; echo "$out"; failed=1; }
# INV-T-18955 (INV-AC-18954): the completeness ratchet (a row for every x-error-codes member new since the base) and the owner ratchet (no silent
# change of a requirement cell). Each case is a throwaway git repository: the base commit holds svc/openapi.yaml (recordDocument: ING-002;
# listThings: PER-009, PER-011), a table with the one row recordDocument.ING-002 (INV-REQ-1) and, when given, the owner map; the mutation runs
# before the check with --base.
OH='table\tidentifier\tkind\tfile\told_requirement\tnew_requirement\truling\n'
OT='svc/required-identifiers.tsv\trecordDocument.ING-002\toperation_error_code\topenapi.yaml'
OL="$OT"'\tINV-REQ-1\tINV-REQ-7\tINV-Q-100\n'
mkc() { # name, owner map lines (printf format, header added; empty = no map) -> $T/<name>
  mkdir -p "$T/$1/svc" "$T/$1/spectral"
  printf 'openapi: 3.1.0\ninfo: {title: f, version: 1.0.0}\npaths:\n  /x:\n    post:\n      operationId: recordDocument\n      x-error-codes: [ING-002]\n  /y:\n    get:\n      operationId: listThings\n      x-error-codes: [PER-009, PER-011]\n' > "$T/$1/svc/openapi.yaml"
  printf 'identifier\tkind\tfile\trequirement\nrecordDocument.ING-002\toperation_error_code\topenapi.yaml\tINV-REQ-1\n' > "$T/$1/svc/required-identifiers.tsv"
  [ -n "$2" ] && printf "${OH}$2" > "$T/$1/spectral/required-identifiers-owners.tsv"
  git -C "$T/$1" init -q && git -C "$T/$1" add -A && git -C "$T/$1" -c user.name=t -c user.email=t@t commit -q -m base
}
ratchet_case() { # name, expected rc, expected text, owner map at the base, mutation run in $T/<name>
  n=$1; want=$2; txt=$3; ml=$4; mkc "$n" "$ml"; shift 4; (cd "$T/$n" && eval "$*")
  out=$(python3 check-required-identifiers.py --base "$(git -C "$T/$n" rev-parse HEAD)" "$T/$n" 2>&1); rc=$?
  if [ "$want" = 0 ] && [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-18954 ratchet case '$n' rejected:"; echo "$out"; failed=1
  elif [ "$want" = 1 ] && [ $rc -eq 0 ]; then echo "gst-ci: FAIL — INV-AC-18954 ratchet case '$n' was accepted"; failed=1
  elif [ "$want" = 1 ] && ! case "$out" in *"$txt"*) true;; *) false;; esac; then echo "gst-ci: FAIL — INV-AC-18954 ratchet case '$n' failed, but not with '$txt':"; echo "$out"; failed=1
  else echo "gst-ci: self-test INV-AC-18954 ratchet '$n' behaved as expected"; fi
}
NEWC="member 'recordDocument.PER-012' of svc/openapi.yaml is new since"
NOROW="no operation_error_code row with an INV-REQ-<number> requirement cell"
ADDC="sed -i 's/x-error-codes: \[ING-002\]/x-error-codes: [ING-002, PER-012]/' svc/openapi.yaml"
ROWC() { echo "printf 'recordDocument.PER-012\\toperation_error_code\\topenapi.yaml\\t$1\\n' >> svc/required-identifiers.tsv"; }
ratchet_case cx-new-with-row 0 "" "" "$ADDC; $(ROWC INV-REQ-5)"
ratchet_case cx-new-no-row 1 "$NEWC" "" "$ADDC"
ratchet_case cx-new-no-row-text 1 "$NOROW" "" "$ADDC"
ratchet_case cx-row-other-operation 1 "$NEWC" "" "$ADDC; printf 'listThings.PER-012\toperation_error_code\topenapi.yaml\tINV-REQ-5\n' >> svc/required-identifiers.tsv; sed -i 's/PER-009, PER-011\]/PER-009, PER-011, PER-012]/' svc/openapi.yaml"
ratchet_case cx-row-lookalike-code 1 "$NEWC" "" "$ADDC; printf 'recordDocument.PER-0121\toperation_error_code\topenapi.yaml\tINV-REQ-5\n' >> svc/required-identifiers.tsv"
ratchet_case cx-row-requirement-r6 1 "$NEWC" "" "$ADDC; $(ROWC R-6)"
ratchet_case cx-row-requirement-question 1 "$NEWC" "" "$ADDC; $(ROWC INV-Q-11264)"
ratchet_case cx-row-requirement-lookalike 1 "$NEWC" "" "$ADDC; $(ROWC INV-REQ-5x)"
ratchet_case cx-row-kind-property 1 "$NEWC" "" "$ADDC; printf 'recordDocument.PER-012\tproperty\topenapi.yaml\tINV-REQ-5\n' >> svc/required-identifiers.tsv"
ratchet_case cx-row-file-other 1 "$NEWC" "" "$ADDC; printf 'recordDocument.PER-012\toperation_error_code\topenapi.yml\tINV-REQ-5\n' >> svc/required-identifiers.tsv; cp svc/openapi.yaml svc/openapi.yml"
ratchet_case cx-code-moved 1 "member 'recordDocument.PER-011' of svc/openapi.yaml is new since" "" "sed -i 's/PER-009, PER-011\]/PER-009]/; s/x-error-codes: \[ING-002\]/x-error-codes: [ING-002, PER-011]/' svc/openapi.yaml"
ratchet_case cx-baseline-no-row 0 "" "" "true"
ratchet_case cx-new-service 0 "" "" "mkdir svc2; cp svc/openapi.yaml svc2/; printf 'identifier\tkind\tfile\trequirement\nrecordDocument.ING-002\toperation_error_code\topenapi.yaml\tINV-REQ-5\nlistThings.PER-009\toperation_error_code\topenapi.yaml\tINV-REQ-5\nlistThings.PER-011\toperation_error_code\topenapi.yaml\tINV-REQ-5\n' > svc2/required-identifiers.tsv"
ratchet_case cx-new-service-no-table 1 "member 'listThings.PER-009' of svc2/openapi.yaml is new since" "" "mkdir svc2; cp svc/openapi.yaml svc2/"
ratchet_case cx-new-service-partial-rows 1 "member 'listThings.PER-011' of svc2/openapi.yaml is new since" "" "mkdir svc2; cp svc/openapi.yaml svc2/; printf 'identifier\tkind\tfile\trequirement\nrecordDocument.ING-002\toperation_error_code\topenapi.yaml\tINV-REQ-5\nlistThings.PER-009\toperation_error_code\topenapi.yaml\tINV-REQ-5\n' > svc2/required-identifiers.tsv"
# a file that cannot be parsed at the base is a finding, never a skip (the base commit is rebuilt with a broken svc/openapi.yaml)
mkc cx-base-broken ""; printf 'paths: [unclosed\n' > "$T/cx-base-broken/svc/openapi.yaml"; git -C "$T/cx-base-broken" -c user.name=t -c user.email=t@t commit -q -am broken
printf "paths: {}\n" > "$T/cx-base-broken/svc/openapi.yaml"
out=$(python3 check-required-identifiers.py --base "$(git -C "$T/cx-base-broken" rev-parse HEAD)" "$T/cx-base-broken" 2>&1); rc=$?
[ $rc -eq 0 ] && { echo "gst-ci: FAIL — INV-AC-18954 ratchet case 'cx-base-broken' was accepted"; failed=1; } || case "$out" in *"cannot be parsed"*) echo "gst-ci: self-test INV-AC-18954 ratchet 'cx-base-broken' behaved as expected";; *) echo "gst-ci: FAIL — cx-base-broken:"; echo "$out"; failed=1;; esac
# without --base the completeness rule does not run
mkc cx-plain ""; (cd "$T/cx-plain" && eval "$ADDC")
out=$(python3 check-required-identifiers.py "$T/cx-plain" 2>&1) && echo "gst-ci: self-test INV-AC-18954 ratchet 'cx-plain' (no --base) behaved as expected" || { echo "gst-ci: FAIL — cx-plain rejected:"; echo "$out"; failed=1; }
# owner rule
SWAPREQ="sed -i 's/INV-REQ-1\$/INV-REQ-7/' svc/required-identifiers.tsv"
OWNTXT="requirement cell changed since"
ratchet_case own-replaced 1 "$OWNTXT" "" "$SWAPREQ"
ratchet_case own-replaced-text 1 "'recordDocument.ING-002' (operation_error_code, openapi.yaml) had 'INV-REQ-1'" "" "$SWAPREQ"
ratchet_case own-second-cell 0 "" "" "printf 'recordDocument.ING-002\toperation_error_code\topenapi.yaml\tINV-REQ-7\n' >> svc/required-identifiers.tsv"
ratchet_case own-map-at-base 0 "" "$OL" "$SWAPREQ"
ratchet_case own-map-wrong-new 1 "$OWNTXT" "$OL" "sed -i 's/INV-REQ-1\$/INV-REQ-8/' svc/required-identifiers.tsv"
ratchet_case own-map-other-old 1 "$OWNTXT" "$OT"'\tINV-REQ-2\tINV-REQ-7\tINV-Q-100\n' "$SWAPREQ"
ratchet_case own-map-other-row 1 "$OWNTXT" 'svc/required-identifiers.tsv\trecordDocument.ING-0021\toperation_error_code\topenapi.yaml\tINV-REQ-1\tINV-REQ-7\tINV-Q-100\n' "$SWAPREQ"
ratchet_case own-map-working-tree-only 1 "$OWNTXT" "" "$SWAPREQ; printf \"\$(printf '$OH$OL')\" > spectral/required-identifiers-owners.tsv"
ratchet_case own-map-ruling-req 1 "map line 2" "$OT"'\tINV-REQ-1\tINV-REQ-7\tINV-REQ-1\n' "$SWAPREQ"
ratchet_case own-map-ruling-req-row 1 "$OWNTXT" "$OT"'\tINV-REQ-1\tINV-REQ-7\tINV-REQ-1\n' "$SWAPREQ"
ratchet_case own-map-ruling-empty 1 "map line 2" "$OT"'\tINV-REQ-1\tINV-REQ-7\t\n' "$SWAPREQ"
ratchet_case own-map-ruling-empty-row 1 "$OWNTXT" "$OT"'\tINV-REQ-1\tINV-REQ-7\t\n' "$SWAPREQ"
ratchet_case own-map-duplicate 1 "map line 3" "$OL$OL" "$SWAPREQ"
ratchet_case own-map-six-cells 1 "map line 2" "$OT"'\tINV-REQ-1\tINV-REQ-7\n' "$SWAPREQ"
ratchet_case own-map-six-cells-row 1 "$OWNTXT" "$OT"'\tINV-REQ-1\tINV-REQ-7\n' "$SWAPREQ"
ratchet_case own-map-old-equals-new 1 "map line 2" "$OT"'\tINV-REQ-1\tINV-REQ-1\tINV-Q-100\n' "true"
ratchet_case own-map-new-not-req 1 "map line 2" "$OT"'\tINV-REQ-1\tR-7\tINV-Q-100\n' "true"
ratchet_case own-map-unknown-row 1 "map line 2" 'svc/required-identifiers.tsv\tnope\toperation_error_code\topenapi.yaml\tINV-REQ-1\tINV-REQ-7\tINV-Q-100\n' "true"
ratchet_case own-map-unknown-table 1 "map line 2" 'svc9/required-identifiers.tsv\trecordDocument.ING-002\toperation_error_code\topenapi.yaml\tINV-REQ-1\tINV-REQ-7\tINV-Q-100\n' "true"
ratchet_case own-map-unused-line 0 "" "$OL" "true"
[ "$failed" -eq 0 ] && echo "gst-ci: check-required-identifiers.py self-test passed."
exit $failed
