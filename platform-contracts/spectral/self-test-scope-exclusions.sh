#!/usr/bin/env sh
# spectral/self-test-scope-exclusions.sh — INV-T-13260, acceptance criteria INV-AC-13258 and INV-AC-13259 (lesson P20).
# Proves spectral/check-scope-exclusions.py fires. Each probe is a temporary copy of the tree (the openapi and
# asyncapi files and the spectral resources) changed alone, never committed; it must make the checker exit 1 with a
# line naming the item. The clean tree exits 0 with 9 flagged, 9 allowed. Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")"
SRC=$(cd .. && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
failed=0
T="$TMP/t"
ALLOWED=spectral/scope-exclusion-allowed.tsv
TERMS=spectral/scope-exclusion-terms.tsv

fresh() { rm -rf "$T"; mkdir -p "$T/spectral"; for f in "$SRC"/*/openapi.yaml "$SRC"/*/asyncapi.yaml; do [ -f "$f" ] || continue; d=$(basename "$(dirname "$f")"); mkdir -p "$T/$d"; cp "$f" "$T/$d/"; done; cp "$SRC/$ALLOWED" "$SRC/$TERMS" "$T/spectral/"; }
# yedit <dir> <file> <python statements on d>: change one YAML file of the temp tree
yedit() { python3 -I - "$T/$1/$2" "$3" <<'PY'
import sys, yaml
f, code = sys.argv[1:3]
with open(f, encoding="utf-8") as h:
    d = yaml.safe_load(h)
exec(code)
with open(f, "w", encoding="utf-8") as h:
    yaml.safe_dump(d, h, sort_keys=False)
PY
}
addop() { yedit "$1" openapi.yaml "d['paths']['$2'] = {'get': {'operationId': '$3', 'responses': {'200': {'description': 'ok'}}}}"; }
# tsv edits with sed on the temp tree (tab is a literal TAB)
TAB=$(printf '\t')
tsv() { sed -i "$1" "$T/$2"; }

expect_pass() { # case, expected output line
  out=$(python3 check-scope-exclusions.py "$T" 2>&1); rc=$?
  if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-13258 '$1' rejected:"; echo "$out"; failed=1
  elif [ "$out" != "$2" ]; then echo "gst-ci: FAIL — INV-AC-13258 '$1' printed '$out', expected '$2'"; failed=1
  else echo "gst-ci: INV-AC-13258 self-test '$1' passed as expected ($2)"; fi
}
expect_fail() { # case, expected message fragment
  out=$(python3 check-scope-exclusions.py "$T" 2>&1); rc=$?
  if [ $rc -ne 1 ]; then echo "gst-ci: FAIL — INV-AC-13259 '$1' exited $rc, expected 1"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: INV-AC-13259 self-test '$1' failed as expected";;
       *) echo "gst-ci: FAIL — INV-AC-13259 '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}

fresh; expect_pass clean "scope-exclusion-check: 9 flagged, 9 allowed"

fresh; addop ingestion /v1/probe-return prepareGstReturn; expect_fail new-return-operation "ingestion/openapi.yaml: operation prepareGstReturn: (a)"
fresh; addop ingestion /v1/payments/{id} listItems; expect_fail neutral-id-payments-path "ingestion/openapi.yaml: operation listItems: (a)"
fresh; addop ingestion /v1/probe-dunning sendDunningNotice; expect_fail new-dunning-operation "ingestion/openapi.yaml: operation sendDunningNotice: (a)"
fresh; yedit reconciliation asyncapi.yaml "d['components']['messages']['PaymentMatched'] = {'name': 'PaymentMatched'}"; expect_fail new-message "reconciliation/asyncapi.yaml: message PaymentMatched: (a)"
fresh; addop ingestion /v1/probe-f5 submitF5Return; expect_fail f5-split "ingestion/openapi.yaml: operation submitF5Return: (a)"
fresh; addop ingestion /v1/probe-ok listItems; expect_pass unflagged-operation "scope-exclusion-check: 9 flagged, 9 allowed"
fresh; addop ingestion /v1/probe-returned listReturned; expect_pass look-alike-token-returned "scope-exclusion-check: 9 flagged, 9 allowed"

fresh; tsv "/${TAB}confirmFiling${TAB}/d" $ALLOWED; expect_fail row-deleted-confirmFiling "submission/openapi.yaml: operation confirmFiling: (a)"
fresh; tsv "/${TAB}changeFilingFrequency${TAB}/d" $ALLOWED; expect_fail row-deleted-changeFilingFrequency "tenancy/openapi.yaml: operation changeFilingFrequency: (a)"
fresh; tsv "s/${TAB}confirmFiling${TAB}/${TAB}confirmFilings${TAB}/" $ALLOWED; expect_fail row-lookalike-confirmFilings "submission: operation confirmFilings: (b)"
fresh; tsv "s/${TAB}changeFilingFrequency${TAB}/${TAB}changeFilingFrequencies${TAB}/" $ALLOWED; expect_fail row-lookalike-changeFilingFrequencies "tenancy: operation changeFilingFrequencies: (b)"
fresh; tsv "s/^operation${TAB}reconciliation${TAB}uploadGstReturn/operation${TAB}submission${TAB}uploadGstReturn/" $ALLOWED; expect_fail row-other-directory "submission: operation uploadGstReturn: (b)"
fresh; tsv "s/^\(operation${TAB}reconciliation${TAB}uploadGstReturn${TAB}\).*/\1not-a-number/" $ALLOWED; expect_fail row-requirement-text "scope-exclusion-allowed.tsv: (d) line"
fresh; tsv "/${TAB}PeriodFilingStatusChanged${TAB}/d" $ALLOWED; expect_fail row-deleted-PeriodFilingStatusChanged "submission/asyncapi.yaml: message PeriodFilingStatusChanged: (a)"
fresh; tsv "s/${TAB}PeriodFilingStatusChanged${TAB}/${TAB}PeriodFilingStatusChange${TAB}/" $ALLOWED; expect_fail row-lookalike-PeriodFilingStatusChange "submission: message PeriodFilingStatusChange: (b)"
fresh; tsv "s/^message${TAB}submission${TAB}PeriodFilingStatusChanged/message${TAB}tenancy${TAB}PeriodFilingStatusChanged/" $ALLOWED; expect_fail row-message-other-directory "tenancy: message PeriodFilingStatusChanged: (b)"
fresh; tsv "s/^\(message${TAB}submission${TAB}PeriodFilingStatusChanged${TAB}\).*/\1INV-REQ-eighty-five/" $ALLOWED; expect_fail row-message-requirement-text "scope-exclusion-allowed.tsv: (d) line"
fresh; printf 'operation\tingestion\tsomeOtherOperation\tINV-REQ-1\n' >> "$T/$ALLOWED"; expect_fail row-for-missing-item "ingestion: operation someOtherOperation: (b)"
fresh; addop ingestion /v1/probe-ok listItems; printf 'operation\tingestion\tlistItems\tINV-REQ-1\n' >> "$T/$ALLOWED"; expect_fail row-for-unflagged-item "ingestion/openapi.yaml: operation listItems: (b)"
fresh; tail -n 1 "$T/$ALLOWED" >> "$T/$ALLOWED"; expect_fail duplicate-row "scope-exclusion-allowed.tsv: (d) line 11 is a duplicate row"
fresh; tsv "1s/.*/kind${TAB}directory${TAB}name${TAB}requirement${TAB}extra/" $ALLOWED; expect_fail changed-header "scope-exclusion-allowed.tsv: (d) the header line must be"
fresh; printf 'operation\tingestion\n' >> "$T/$ALLOWED"; expect_fail row-width "scope-exclusion-allowed.tsv: (d) line 11 has 2 fields, expected 4"
fresh; printf 'INV-REQ-2562\tReturn\n' >> "$T/$TERMS"; expect_fail upper-case-term "scope-exclusion-terms.tsv: (d) line"
fresh; printf 'INV-REQ-2562\tre-turn\n' >> "$T/$TERMS"; expect_fail punctuated-term "scope-exclusion-terms.tsv: (d) line"
fresh; printf 'INV-REQ-2562\n' >> "$T/$TERMS"; expect_fail terms-row-width "scope-exclusion-terms.tsv: (d) line"
fresh; printf 'requirement\tterm\n' > "$T/$TERMS"; expect_fail empty-terms "scope-exclusion-terms.tsv: (d) the terms table is empty"
fresh; printf 'requirement\tterm\nINV-REQ-2562\treturn\nINV-REQ-2562\treturn\n' > "$T/$TERMS"; expect_fail duplicate-term "scope-exclusion-terms.tsv: (d) line 3 is a duplicate row"
fresh; tsv "s/^INV-REQ-2562${TAB}return$/INV-REQ-25x2${TAB}return/" $TERMS; expect_fail term-requirement-text "scope-exclusion-terms.tsv: (d) line"
fresh; yedit submission openapi.yaml "
for p in d['paths'].values():
    for m in p.values():
        if isinstance(m, dict) and m.get('operationId') == 'confirmFiling':
            del m['operationId']"; expect_fail operationId-removed "submission/openapi.yaml: POST"
fresh; rm "$T/$ALLOWED"; expect_fail tsv-unreadable "scope-exclusion-allowed.tsv: (e) unreadable"
fresh; printf 'a: [unclosed\n' > "$T/ingestion/openapi.yaml"; expect_fail yaml-unreadable "ingestion/openapi.yaml: (e) unreadable YAML"
fresh; rm -f "$T"/*/openapi.yaml; expect_fail no-openapi "no */openapi.yaml found"

# the repository itself passes
out=$(python3 check-scope-exclusions.py .. 2>&1); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-13258 repository tree rejected:"; echo "$out"; failed=1; fi

[ "$failed" -eq 0 ] && echo "gst-ci: INV-AC-13258 INV-AC-13259 check-scope-exclusions.py self-test passed."
exit $failed
