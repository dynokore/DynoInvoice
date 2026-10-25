#!/usr/bin/env sh
# spectral/self-test-degraded-allow.sh — INV-T-9077, acceptance criterion INV-AC-9075 (INV-Q-9065 A, lesson P20).
# Proves spectral/check-degraded-allow.py fires. Each probe is a temporary copy of the tree (the openapi files and
# the spectral resource) changed alone, never committed; it must make the checker exit 1 with a line naming the
# operation. The clean tree exits 0. Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")"
SRC=$(cd .. && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
failed=0
OP_OK="ingestion POST /v1/transactions"          # x-principal: ingest
OP_INT="canonical POST /internal/v1/canonical/documents"
ROW_OK="ingestion	POST	/v1/transactions"
TSV=spectral/degraded-allow-operations.tsv

fresh() { rm -rf "$TMP/t"; mkdir -p "$TMP/t/spectral"; for f in "$SRC"/*/openapi.yaml; do d=$(basename "$(dirname "$f")"); mkdir "$TMP/t/$d"; cp "$f" "$TMP/t/$d/"; done; printf 'directory\tmethod\tpath\n' > "$TMP/t/$TSV"; sed -i '/^      x-degraded-allow: true$/d' "$TMP"/t/*/openapi.yaml; if grep -q 'x-degraded-allow' "$TMP"/t/*/openapi.yaml; then echo "gst-ci: FAIL — INV-AC-9075 the baseline tree still carries a marker"; failed=1; fi; }
# mark <dir> <METHOD> <path> <yaml value> [principal]: put x-degraded-allow into that operation of the temp tree
mark() { python3 -I - "$TMP/t" "$@" <<'PY'
import sys
root, d, method, path, value = sys.argv[1:6]
principal = sys.argv[6] if len(sys.argv) > 6 else None
f = f"{root}/{d}/openapi.yaml"
lines = open(f, encoding="utf-8").read().split("\n")
i = lines.index(f"  {path}:")
j = next(k for k in range(i + 1, len(lines)) if lines[k] == f"    {method.lower()}:")
end = next((k for k in range(j + 1, len(lines)) if lines[k].strip() and len(lines[k]) - len(lines[k].lstrip()) <= 4), len(lines))
if principal:
    k = next(k for k in range(j, end) if lines[k].startswith("      x-principal:"))
    lines[k] = f"      x-principal: {principal}"
lines.insert(j + 1, f"      x-degraded-allow: {value}")
open(f, "w", encoding="utf-8").write("\n".join(lines))
PY
}
row() { printf '%s\n' "$1" >> "$TMP/t/$TSV"; }

expect_pass() { # case, expected output line
  out=$(python3 check-degraded-allow.py "$TMP/t" 2>&1); rc=$?
  if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-9075 '$1' rejected:"; echo "$out"; failed=1
  elif [ "$out" != "$2" ]; then echo "gst-ci: FAIL — INV-AC-9075 '$1' printed '$out', expected '$2'"; failed=1
  else echo "gst-ci: INV-AC-9075 self-test '$1' passed as expected ($2)"; fi
}
expect_fail() { # case, expected message fragment
  out=$(python3 check-degraded-allow.py "$TMP/t" 2>&1); rc=$?
  if [ $rc -ne 1 ]; then echo "gst-ci: FAIL — INV-AC-9075 '$1' exited $rc, expected 1"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: INV-AC-9075 self-test '$1' failed as expected";;
       *) echo "gst-ci: FAIL — INV-AC-9075 '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}

fresh; expect_pass clean "degraded-allow-check: 0 marked"
fresh; mark $OP_OK true; row "$ROW_OK"; expect_pass marked-with-row "degraded-allow-check: 1 marked"

fresh; mark $OP_OK false; row "$ROW_OK"; expect_fail value-false "ingestion: POST /v1/transactions: (a) x-degraded-allow value False"
fresh; mark $OP_OK '"true"'; row "$ROW_OK"; expect_fail value-string-true "ingestion: POST /v1/transactions: (a) x-degraded-allow value 'true'"
fresh; mark $OP_OK 1; row "$ROW_OK"; expect_fail value-one "ingestion: POST /v1/transactions: (a) x-degraded-allow value 1"
fresh; mark $OP_OK '[true]'; row "$ROW_OK"; expect_fail value-sequence "ingestion: POST /v1/transactions: (a) x-degraded-allow value [True]"
fresh; mark $OP_INT true; row "canonical	POST	/internal/v1/canonical/documents"; expect_fail internal-path "canonical: POST /internal/v1/canonical/documents: (b) a marked operation's path"
fresh; mark $OP_OK true workload; row "$ROW_OK"; expect_fail principal-workload "ingestion: POST /v1/transactions: (b) x-principal 'workload'"
fresh; mark $OP_OK true ingest-x; row "$ROW_OK"; expect_fail principal-lookalike "ingestion: POST /v1/transactions: (b) x-principal 'ingest-x'"
# a /v10/ look-alike path: rename the path of a copy of the operation's file
fresh; mark $OP_OK true; sed -i 's#^  /v1/transactions:#  /v10/transactions:#' "$TMP/t/ingestion/openapi.yaml"; row "ingestion	POST	/v10/transactions"
expect_fail path-v10-lookalike "ingestion: POST /v10/transactions: (b) a marked operation's path"
fresh; mark $OP_OK true; expect_fail marked-without-row "ingestion: POST /v1/transactions: (c) marked operation has no row"
fresh; row "$ROW_OK"; expect_fail row-without-marker "ingestion: POST /v1/transactions: (c) row of degraded-allow-operations.tsv has no marked operation"
fresh; row "ingestion	POST	/v1/no-such-operation"; expect_fail row-for-missing-operation "ingestion: POST /v1/no-such-operation: (c) row of"
fresh; mark $OP_OK true; row "$ROW_OK"; row "$ROW_OK"; expect_fail duplicate-row "degraded-allow-operations.tsv: line 3 is a duplicate row"
fresh; printf 'directory\tmethod\tpath\textra\n' > "$TMP/t/$TSV"; expect_fail changed-header "degraded-allow-operations.tsv: the header line must be directory	method	path"
fresh; row "ingestion	POST"; expect_fail row-width "degraded-allow-operations.tsv: line 2 has 2 fields, expected 3"
fresh; rm "$TMP/t/$TSV"; expect_fail tsv-unreadable "degraded-allow-operations.tsv: unreadable"

# the repository itself passes
out=$(python3 check-degraded-allow.py .. 2>&1); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-9075 repository tree rejected:"; echo "$out"; failed=1; fi

[ "$failed" -eq 0 ] && echo "gst-ci: INV-AC-9075 check-degraded-allow.py self-test passed."
exit $failed
