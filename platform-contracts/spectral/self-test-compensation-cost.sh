#!/usr/bin/env sh
# spectral/self-test-compensation-cost.sh — INV-T-15754, acceptance criterion INV-AC-15750 (INV-Q-15744).
# Proves spectral/check-compensation-cost.py fires. Each probe is a temporary copy of the tree (the openapi files and
# the spectral resource) changed alone, never committed. The unchanged tree and the passing variants must print the
# exact output line; every other probe must exit 1 with a line naming the operation. Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")"
SRC=$(cd .. && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
failed=0
TSV=spectral/compensation-cost-staged.tsv
N=$(python3 -I - "$SRC" <<'PY'
import sys, yaml, glob
n = 0
for f in glob.glob(sys.argv[1] + "/*/openapi.yaml"):
    for item in (yaml.safe_load(open(f, encoding="utf-8")).get("paths") or {}).values():
        for m, o in item.items():
            if isinstance(o, dict) and isinstance(o.get("x-operation"), dict) and o["x-operation"].get("reversibility") == "COMPENSABLE":
                n += 1
print(n)
PY
)
S=$(($(wc -l < "$SRC/$TSV") - 1))
TEXT="Correctable by a superseding pack, but both activations remain in the audit trail."

fresh() { rm -rf "$TMP/t"; mkdir -p "$TMP/t/spectral"; for f in "$SRC"/*/openapi.yaml; do d=$(basename "$(dirname "$f")"); mkdir "$TMP/t/$d"; cp "$f" "$TMP/t/$d/"; done; cp "$SRC/$TSV" "$TMP/t/$TSV"; cp "$SRC/rules/spec-facts.tsv" "$TMP/t/rules/"; }
# addop <dir> <operationId> <statement|-> : append an extra COMPENSABLE operation (with the statement unless -)
addop() { python3 -I - "$TMP/t" "$@" <<'PY'
import sys
root, d, op, value = sys.argv[1:5]
f = f"{root}/{d}/openapi.yaml"
text = open(f, encoding="utf-8").read()
cost = "" if value == "-" else f"        compensationCost: {value}\n"
block = f"  /v1/probe-{d}-{op}:\n    post:\n      operationId: {op}\n      x-operation:\n        reversibility: COMPENSABLE\n{cost}"
text = text.replace("\npaths:\n", "\npaths:\n" + block, 1)
open(f, "w", encoding="utf-8").write(text)
PY
}
# setcost <dir> <operationId> <value|-> : replace (or, with -, remove) the compensationCost line of that operation
setcost() { python3 -I - "$TMP/t" "$@" <<'PY'
import sys
root, d, op, value = sys.argv[1:5]
f = f"{root}/{d}/openapi.yaml"
lines = open(f, encoding="utf-8").read().split("\n")
i = next(k for k, l in enumerate(lines) if l == f"      operationId: {op}")
j = next(k for k in range(i, len(lines)) if lines[k].startswith("        compensationCost:"))
if value == "-":
    del lines[j]
else:
    lines[j] = f"        compensationCost: {value}"
open(f, "w", encoding="utf-8").write("\n".join(lines))
PY
}
row() { printf '%s\n' "$1" >> "$TMP/t/$TSV"; }

expect_pass() { # case, expected output line
  out=$(python3 check-compensation-cost.py "$TMP/t" 2>&1); rc=$?
  if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-15750 '$1' rejected:"; echo "$out"; failed=1
  elif [ "$out" != "$2" ]; then echo "gst-ci: FAIL — INV-AC-15750 '$1' printed '$out', expected '$2'"; failed=1
  else echo "gst-ci: INV-AC-15750 self-test '$1' passed as expected ($2)"; fi
}
expect_fail() { # case, expected message fragment
  out=$(python3 check-compensation-cost.py "$TMP/t" 2>&1); rc=$?
  if [ $rc -ne 1 ]; then echo "gst-ci: FAIL — INV-AC-15750 '$1' exited $rc, expected 1"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: INV-AC-15750 self-test '$1' failed as expected";;
       *) echo "gst-ci: FAIL — INV-AC-15750 '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}

fresh; expect_pass clean "compensation-cost-check: $N compensable operations, $S staged"
fresh; setcost rules armRegulatoryPack -; expect_fail arm-statement-removed "rules/openapi.yaml: operation armRegulatoryPack"
fresh; addop tenancy probeOp -; expect_fail new-op-no-statement "tenancy/openapi.yaml: operation probeOp"
fresh; addop tenancy probeOp "\"$TEXT\""; expect_pass new-op-with-statement "compensation-cost-check: $((N + 1)) compensable operations, $S staged"
fresh; addop tenancy probeOp -; row "tenancy	probeOp"; expect_pass new-op-with-row "compensation-cost-check: $((N + 1)) compensable operations, $((S + 1)) staged"
fresh; row "rules	armRegulatoryPack"; expect_fail row-for-op-with-statement "rules/openapi.yaml: operation armRegulatoryPack: already carries a compensationCost statement, remove the staged row"
fresh; row "tenancy	noSuchOperation"; expect_fail row-for-missing-op "tenancy/openapi.yaml: operation noSuchOperation: stale staged row"
fresh; row "submission	armRegulatoryPack"; expect_fail row-name-in-other-directory "submission/openapi.yaml: operation armRegulatoryPack: stale staged row"
fresh; addop tenancy armRegulatoryPack -; expect_fail same-name-other-directory "tenancy/openapi.yaml: operation armRegulatoryPack: COMPENSABLE without"
fresh; first=$(sed -n 2p "$TMP/t/$TSV"); row "$first"; expect_fail duplicate-row "compensation-cost-staged.tsv: line $((S + 2)) is a duplicate row"
fresh; row "tenancy"; expect_fail short-row "compensation-cost-staged.tsv: line $((S + 2)) has 1 fields, expected 2"
fresh; row "tenancy	a	b"; expect_fail long-row "compensation-cost-staged.tsv: line $((S + 2)) has 3 fields, expected 2"
fresh; sed -i '1s/.*/directory\toperation/' "$TMP/t/$TSV"; expect_fail changed-header "compensation-cost-staged.tsv: the header line must be directory	operationId"
fresh; rm "$TMP/t/$TSV"; expect_fail tsv-deleted "compensation-cost-staged.tsv: unreadable"
fresh; printf 'a: [\n' > "$TMP/t/submission/openapi.yaml"; expect_fail yaml-unreadable "submission/openapi.yaml: unreadable YAML"
fresh; setcost rules armRegulatoryPack '"   "'; expect_fail statement-of-spaces "rules/openapi.yaml: operation armRegulatoryPack"
fresh; setcost rules armRegulatoryPack '[x]'; expect_fail statement-not-string "rules/openapi.yaml: operation armRegulatoryPack"
fresh; addop rules armRegulatoryPack "\"$TEXT\""; expect_fail operation-declared-twice "rules/openapi.yaml: operation armRegulatoryPack: declared in 2 operations"

# the pin (INV-AC-16921): each case runs the cost check and the spec-facts check on one changed copy and asserts the exit pair
PIN="rules/openapi.yaml"
expect_pair() { # case, expected cost exit, expected spec-facts exit, fragment the spec-facts output must hold (or -)
  out1=$(python3 check-compensation-cost.py "$TMP/t" 2>&1); rc1=$?
  out2=$(python3 check-spec-facts.py "$TMP/t" 2>&1); rc2=$?
  if [ $rc1 -ne "$2" ] || [ $rc2 -ne "$3" ]; then echo "gst-ci: FAIL — INV-AC-16921 '$1' exits $rc1/$rc2, expected $2/$3"; echo "$out1"; echo "$out2"; failed=1
  elif [ "$4" != "-" ] && ! printf '%s' "$out2" | grep -qF -- "$4"; then echo "gst-ci: FAIL — INV-AC-16921 '$1' spec-facts output lacks '$4':"; echo "$out2"; failed=1
  else echo "gst-ci: INV-AC-16921 self-test '$1' exits $rc1/$rc2 as expected"; fi
}
ARMPTR="/paths/~1internal~1v1~1rules~1regulatory-packs~1{packType}~1{version}~1arming/post/x-operation/compensationCost"
fresh; expect_pair pin-clean 0 0 -
fresh; setcost rules armRegulatoryPack '"Correctable."'; expect_pair arm-text-non-blank-other 0 1 "$ARMPTR"
fresh; sed -i 's/activations remain in/activations remains in/' "$TMP/t/$PIN"; expect_pair arm-text-remains 0 1 "$ARMPTR"
fresh; setcost rules armRegulatoryPack -; expect_pair arm-text-removed 1 1 "$ARMPTR"
fresh; addop tenancy probeOp "\"$TEXT\""; expect_pair other-op-statement 0 0 -
fresh; addop tenancy probeOp "\"$TEXT.\""; expect_pair other-op-statement-trailing-change 0 0 -

# the repository itself passes
out=$(python3 check-compensation-cost.py .. 2>&1); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-15750 repository tree rejected:"; echo "$out"; failed=1; fi

out=$(python3 check-spec-facts.py .. 2>&1); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-16921 repository tree rejected by check-spec-facts:"; echo "$out"; failed=1; fi

[ "$failed" -eq 0 ] && echo "gst-ci: INV-AC-15750 check-compensation-cost.py self-test passed."
exit $failed
