#!/usr/bin/env sh
# spectral/self-test-reversibility-floor.sh — INV-T-13354, acceptance criteria INV-AC-13350 and INV-AC-13351 (INV-Q-13344).
# Proves spectral/check-reversibility-floor.py fires. Each probe is a temporary copy of the tree (the openapi files and
# the spectral resource) changed alone, never committed. The unchanged tree and the stricter-than-floor probes pass;
# every other probe must exit 1 with a line naming the operation. Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")"
SRC=$(cd .. && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
failed=0
TSV=spectral/reversibility-floor.tsv
N=10

fresh() { rm -rf "$TMP/t"; mkdir -p "$TMP/t/spectral"; for f in "$SRC"/*/openapi.yaml; do d=$(basename "$(dirname "$f")"); mkdir "$TMP/t/$d"; cp "$f" "$TMP/t/$d/"; done; cp "$SRC/$TSV" "$TMP/t/$TSV"; }
# setclass <dir> <operationId> <value|-> : replace (or, with -, remove) the reversibility line of that operation
setclass() { python3 -I - "$TMP/t" "$@" <<'PY'
import sys
root, d, op, value = sys.argv[1:5]
f = f"{root}/{d}/openapi.yaml"
lines = open(f, encoding="utf-8").read().split("\n")
i = next(k for k, l in enumerate(lines) if l == f"      operationId: {op}")
j = next(k for k in range(i, len(lines)) if lines[k] and lines[k].startswith("        reversibility:"))
if value == "-":
    del lines[j]
else:
    lines[j] = f"        reversibility: {value}"
open(f, "w", encoding="utf-8").write("\n".join(lines))
PY
}
# addop <dir> <operationId> <value> : append a copy-free extra operation to that file
addop() { python3 -I - "$TMP/t" "$@" <<'PY'
import sys
root, d, op, value = sys.argv[1:5]
f = f"{root}/{d}/openapi.yaml"
text = open(f, encoding="utf-8").read()
block = f"  /v1/probe-{op}:\n    post:\n      operationId: {op}\n      x-operation:\n        reversibility: {value}\n"
text = text.replace("\npaths:\n", "\npaths:\n" + block, 1)
open(f, "w", encoding="utf-8").write(text)
PY
}
row() { printf '%s\n' "$1" >> "$TMP/t/$TSV"; }

expect_pass() { # case, expected output line
  out=$(python3 check-reversibility-floor.py "$TMP/t" 2>&1); rc=$?
  if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-13350 '$1' rejected:"; echo "$out"; failed=1
  elif [ "$out" != "$2" ]; then echo "gst-ci: FAIL — INV-AC-13350 '$1' printed '$out', expected '$2'"; failed=1
  else echo "gst-ci: INV-AC-13350 self-test '$1' passed as expected ($2)"; fi
}
expect_fail() { # case, expected message fragment
  out=$(python3 check-reversibility-floor.py "$TMP/t" 2>&1); rc=$?
  if [ $rc -ne 1 ]; then echo "gst-ci: FAIL — INV-AC-13350 '$1' exited $rc, expected 1"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: INV-AC-13350 self-test '$1' failed as expected";;
       *) echo "gst-ci: FAIL — INV-AC-13350 '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}

fresh; expect_pass clean "reversibility-floor-check: $N rows"
fresh; setclass submission drainPeriod COMPENSABLE; expect_fail drainPeriod-compensable "submission/openapi.yaml: operation drainPeriod: declared COMPENSABLE is lower than the floor IRREVERSIBLE"
fresh; setclass submission replaySubmissionsByScope UNDOABLE; expect_fail replayByScope-undoable "submission/openapi.yaml: operation replaySubmissionsByScope: declared UNDOABLE is lower"
fresh; setclass masterdata configureIdentityTriple UNDOABLE; expect_fail configureIdentityTriple-undoable "masterdata/openapi.yaml: operation configureIdentityTriple: declared UNDOABLE is lower"
fresh; setclass notification setAlertPolicy IRREVERSIBLE; expect_pass stricter-than-floor "reversibility-floor-check: $N rows"
fresh; setclass tenancy goLive irreversible; expect_fail goLive-lowercase "tenancy/openapi.yaml: operation goLive: x-operation.reversibility 'irreversible' is not one of"
fresh; setclass tenancy goLive "'IRREVERSIBLE '"; expect_fail goLive-trailing-space "tenancy/openapi.yaml: operation goLive: x-operation.reversibility 'IRREVERSIBLE ' is not one of"
fresh; setclass evidence deleteAtRetentionEnd -; expect_fail reversibility-removed "evidence/openapi.yaml: operation deleteAtRetentionEnd: x-operation.reversibility None is absent"
fresh; setclass evidence deleteAtRetentionEnd '[IRREVERSIBLE]'; expect_fail reversibility-not-string "evidence/openapi.yaml: operation deleteAtRetentionEnd: x-operation.reversibility ['IRREVERSIBLE'] is absent or not a string"
fresh; sed -i 's/operationId: drainPeriod$/operationId: drainPeriods/' "$TMP/t/submission/openapi.yaml"; expect_fail operation-renamed "submission/openapi.yaml: operation drainPeriod: missing from that directory"
fresh; addop tenancy drainPeriod COMPENSABLE; expect_pass same-name-other-directory "reversibility-floor-check: $N rows"
fresh; addop tenancy drainPeriod COMPENSABLE; sed -i 's/operationId: drainPeriod$/operationId: drainPeriods/' "$TMP/t/submission/openapi.yaml"; expect_fail same-name-only-elsewhere "submission/openapi.yaml: operation drainPeriod: missing from that directory"
fresh; addop submission drainPeriod IRREVERSIBLE; expect_fail operation-declared-twice "submission/openapi.yaml: operation drainPeriod: declared in 2 operations"
fresh; row "submission	drainPeriod	IRREVERSIBLE "; expect_fail floor-trailing-space "reversibility-floor.tsv: line 12 floor 'IRREVERSIBLE ' is not one of"
fresh; row "submission	drainPeriod	IRREVERSIBLE"; expect_fail duplicate-row "reversibility-floor.tsv: line 12 is a duplicate row"
fresh; row "submission	drainPeriod"; expect_fail row-width "reversibility-floor.tsv: line 12 has 2 fields, expected 3"
fresh; printf 'directory\toperationId\tfloor\textra\n' > "$TMP/t/$TSV"; expect_fail changed-header "reversibility-floor.tsv: the header line must be directory	operationId	floor"
fresh; rm "$TMP/t/$TSV"; expect_fail tsv-deleted "reversibility-floor.tsv: unreadable"
fresh; printf 'a: [\n' > "$TMP/t/submission/openapi.yaml"; expect_fail yaml-unreadable "submission/openapi.yaml: unreadable YAML"

# the repository itself passes
out=$(python3 check-reversibility-floor.py .. 2>&1); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-13350 repository tree rejected:"; echo "$out"; failed=1; fi

[ "$failed" -eq 0 ] && echo "gst-ci: INV-AC-13350 check-reversibility-floor.py self-test passed."
exit $failed
