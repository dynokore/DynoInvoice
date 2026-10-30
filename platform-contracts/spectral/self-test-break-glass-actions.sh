#!/usr/bin/env sh
# spectral/self-test-break-glass-actions.sh — INV-T-9308, acceptance criterion INV-AC-9304 (INV-Q-9119 A, lesson P20).
# Proves spectral/check-break-glass-actions.py fires. Each probe is a temporary copy of the tree (the openapi files and
# the spectral resource) changed alone, never committed; it must make the checker exit 1 with a line naming the
# operation. The clean tree exits 0. Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")"
SRC=$(cd .. && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
failed=0
OP_OK="submission GET /v1/entities/{entityRef}/periods/{periodRef}/readiness"   # end-user, filing-workflow
OP_INT="submission POST /internal/v1/transactions/{docUuid}/versions/{version}/replay"
ROW_OK="submission	GET	/v1/entities/{entityRef}/periods/{periodRef}/readiness	READINESS"
TSV=spectral/break-glass-actions-operations.tsv

fresh() { rm -rf "$TMP/t"; mkdir -p "$TMP/t/spectral"; for f in "$SRC"/*/openapi.yaml; do d=$(basename "$(dirname "$f")"); mkdir "$TMP/t/$d"; cp "$f" "$TMP/t/$d/"; done; printf 'directory\tmethod\tpath\taction\n' > "$TMP/t/$TSV"; sed -i '/^      x-break-glass-action: /d' "$TMP"/t/*/openapi.yaml; if grep -q 'x-break-glass-action' "$TMP"/t/*/openapi.yaml; then echo "gst-ci: FAIL — INV-AC-9304 the baseline tree still carries a marker"; failed=1; fi; }
# mark <dir> <METHOD> <path> <yaml value> [key=value ...]: put x-break-glass-action into that operation of the temp
# tree; each key=value replaces the operation's x-principal / x-caller-auth line (or adds it)
mark() { python3 -I - "$TMP/t" "$@" <<'PY'
import sys
root, d, method, path, value = sys.argv[1:6]
f = f"{root}/{d}/openapi.yaml"
lines = open(f, encoding="utf-8").read().split("\n")
i = lines.index(f"  {path}:")
j = next(k for k in range(i + 1, len(lines)) if lines[k] == f"    {method.lower()}:")
end = next((k for k in range(j + 1, len(lines)) if lines[k].strip() and len(lines[k]) - len(lines[k].lstrip()) <= 4), len(lines))
for kv in sys.argv[6:]:
    key, val = kv.split("=", 1)
    k = next((k for k in range(j, end) if lines[k].startswith(f"      {key}:")), None)
    if k is None:
        lines.insert(j + 1, f"      {key}: {val}")
    else:
        lines[k] = f"      {key}: {val}"
lines.insert(j + 1, f"      x-break-glass-action: {value}")
open(f, "w", encoding="utf-8").write("\n".join(lines))
PY
}
row() { printf '%s\n' "$1" >> "$TMP/t/$TSV"; }

expect_pass() { # case, expected output line
  out=$(python3 check-break-glass-actions.py "$TMP/t" 2>&1); rc=$?
  if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-9304 '$1' rejected:"; echo "$out"; failed=1
  elif [ "$out" != "$2" ]; then echo "gst-ci: FAIL — INV-AC-9304 '$1' printed '$out', expected '$2'"; failed=1
  else echo "gst-ci: INV-AC-9304 self-test '$1' passed as expected ($2)"; fi
}
expect_fail() { # case, expected message fragment
  out=$(python3 check-break-glass-actions.py "$TMP/t" 2>&1); rc=$?
  if [ $rc -ne 1 ]; then echo "gst-ci: FAIL — INV-AC-9304 '$1' exited $rc, expected 1"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: INV-AC-9304 self-test '$1' failed as expected";;
       *) echo "gst-ci: FAIL — INV-AC-9304 '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}

fresh; expect_pass clean "break-glass-actions-check: 0 marked"
fresh; mark $OP_OK READINESS; row "$ROW_OK"; expect_pass marked-with-row "break-glass-actions-check: 1 marked"
fresh; mark $OP_OK READINESS x-caller-auth=end-user; row "$ROW_OK"; expect_pass marked-end-user "break-glass-actions-check: 1 marked"

W="submission: GET /v1/entities/{entityRef}/periods/{periodRef}/readiness"
fresh; mark $OP_OK view; row "${ROW_OK%READINESS}view"; expect_fail value-lower-case "$W: (a) x-break-glass-action value 'view'"
fresh; mark $OP_OK '[VIEW]'; row "${ROW_OK%READINESS}VIEW"; expect_fail value-sequence "$W: (a) x-break-glass-action value ['VIEW']"
fresh; mark $OP_OK ARCHIVE; row "${ROW_OK%READINESS}ARCHIVE"; expect_fail value-not-member "$W: (a) x-break-glass-action value 'ARCHIVE'"
fresh; mark $OP_OK true; row "${ROW_OK%READINESS}true"; expect_fail value-boolean "$W: (a) x-break-glass-action value True"
fresh; mark $OP_OK 1; row "${ROW_OK%READINESS}1"; expect_fail value-number "$W: (a) x-break-glass-action value 1"
fresh; mark $OP_OK READINESS x-caller-auth=signed-link; row "$ROW_OK"; expect_fail caller-auth-signed-link "$W: (b) x-caller-auth 'signed-link'"
fresh; mark $OP_OK READINESS x-principal=workload; row "$ROW_OK"; expect_fail principal-workload "$W: (b) x-principal workload"
# a /v10/ look-alike path: rename the path of a copy of the operation's file
fresh; mark $OP_OK READINESS; sed -i 's#^  /v1/entities/{entityRef}/periods/{periodRef}/readiness:#  /v10/entities/{entityRef}/periods/{periodRef}/readiness:#' "$TMP/t/submission/openapi.yaml"; row "submission	GET	/v10/entities/{entityRef}/periods/{periodRef}/readiness	READINESS"
expect_fail path-v10-lookalike "submission: GET /v10/entities/{entityRef}/periods/{periodRef}/readiness: (b) a marked operation's path"
fresh; mark $OP_INT REPLAY; row "submission	POST	/internal/v1/transactions/{docUuid}/versions/{version}/replay	REPLAY"; expect_pass internal-path-allowed "break-glass-actions-check: 1 marked"
fresh; mark $OP_OK READINESS; expect_fail marked-without-row "$W: (c) marked operation has no row"
fresh; row "$ROW_OK"; expect_fail row-without-marker "$W: (c) row of break-glass-actions-operations.tsv has no marked operation"
fresh; mark $OP_OK READINESS; row "${ROW_OK%READINESS}DRAIN"; expect_fail row-action-differs "$W: (c) row action 'DRAIN' differs from the marker 'READINESS'"
fresh; row "submission	GET	/v1/no-such-operation	VIEW"; expect_fail row-for-missing-operation "submission: GET /v1/no-such-operation: (c) row of"
fresh; mark $OP_OK READINESS; row "$ROW_OK"; row "$ROW_OK"; expect_fail duplicate-row "break-glass-actions-operations.tsv: line 3 is a duplicate row"
fresh; printf 'directory\tmethod\tpath\taction\textra\n' > "$TMP/t/$TSV"; expect_fail changed-header "break-glass-actions-operations.tsv: the header line must be directory	method	path	action"
fresh; row "submission	GET	/v1/x"; expect_fail row-width "break-glass-actions-operations.tsv: line 2 has 3 fields, expected 4"
fresh; rm "$TMP/t/$TSV"; expect_fail tsv-unreadable "break-glass-actions-operations.tsv: unreadable"
fresh; printf 'x-break-glass-action\tstring\n# x-break-glass-action in a comment is fine\n' > "$TMP/t/access/required-identifiers.tsv"; expect_fail required-identifiers-row "access/required-identifiers.tsv: x-break-glass-action is an extension key"

# the repository itself passes
out=$(python3 check-break-glass-actions.py .. 2>&1); rc=$?
if [ $rc -ne 0 ]; then echo "gst-ci: FAIL — INV-AC-9304 repository tree rejected:"; echo "$out"; failed=1; fi

[ "$failed" -eq 0 ] && echo "gst-ci: INV-AC-9304 check-break-glass-actions.py self-test passed."
exit $failed
