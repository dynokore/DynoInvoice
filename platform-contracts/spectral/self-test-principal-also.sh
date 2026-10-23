#!/usr/bin/env sh
# spectral/self-test-principal-also.sh — INV-T-9152, acceptance criterion INV-AC-9149 (AUTHZ-01, lesson P20).
# Proves spectral/check-principal-also.py fires: the clean tree exits 0; each probe is a copy of the tree changed
# in one place (nothing is committed) and must make the checker exit 1 with a line naming the operation.
# Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
failed=0
MARK='      x-principal-also: certification'
PROBE_OP='  /v10/probe:
    post:
      operationId: probeOp
      x-principal: PRINCIPAL
MARKLINE
      responses:
        "200":
          description: ok
'

fresh() { # name -> a copy of the tree (every */openapi.yaml and the TSV) in $TMP/name
  mkdir -p "$TMP/$1/spectral"
  for f in */openapi.yaml; do mkdir -p "$TMP/$1/$(dirname "$f")"; cp "$f" "$TMP/$1/$f"; done
  cp spectral/principal-also-operations.tsv "$TMP/$1/spectral/"
}
mutate() { # name, python statements on t (openapi text) and v (tsv text); both written back
  python3 -I - "$TMP/$1" "$2" <<'PY'
import sys
root, code = sys.argv[1], sys.argv[2]
o, tsv = root + "/ingestion/openapi.yaml", root + "/spectral/principal-also-operations.tsv"
t, v = open(o).read(), open(tsv).read()
before = (t, v)
exec(code)
assert (t, v) != before, "probe changed nothing"
open(o, "w").write(t)
open(tsv, "w").write(v)
PY
}
probe() { # name, expected fragment, python code
  fresh "$1"
  if ! mutate "$1" "$3"; then echo "gst-ci: FAIL — INV-AC-9149 probe '$1' could not be built"; failed=1; return; fi
  out=$(python3 spectral/check-principal-also.py "$TMP/$1" 2>&1); rc=$?
  if [ $rc -ne 1 ]; then echo "gst-ci: FAIL — INV-AC-9149 probe '$1' exited $rc, expected 1"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: INV-AC-9149 probe '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — INV-AC-9149 probe '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}

fresh clean
out=$(python3 spectral/check-principal-also.py "$TMP/clean" 2>&1); rc=$?
if [ $rc -ne 0 ] || [ "$out" != "principal-also-check: 7 marked" ]; then
  echo "gst-ci: FAIL — INV-AC-9149 clean tree: exit $rc, '$out'"; failed=1
else echo "gst-ci: INV-AC-9149 clean tree passed as expected ($out)"; fi

NEWOP="t = t.replace('  /internal/v1/ingestion/test-events:', '''$PROBE_OP'''.replace('PRINCIPAL', '%s').replace('MARKLINE', '''$MARK''') + '  /internal/v1/ingestion/test-events:', 1)"
probe value-capital "ingestion: POST /v1/transactions: (a)" "t = t.replace('x-principal-also: certification', 'x-principal-also: Certification', 1)"
probe value-trailing-space "ingestion: POST /v1/transactions: (a)" "t = t.replace('x-principal-also: certification', 'x-principal-also: \"certification \"', 1)"
probe value-boolean "ingestion: POST /v1/transactions: (a)" "t = t.replace('x-principal-also: certification', 'x-principal-also: true', 1)"
probe value-sequence "ingestion: POST /v1/transactions: (a)" "t = t.replace('x-principal-also: certification', 'x-principal-also: [certification]', 1)"
probe internal-path "ingestion: POST /internal/v1/ingestion/test-events: (b) path" "t = t.replace('      operationId: sendTestEvent\n', '      operationId: sendTestEvent\n$MARK\n', 1)"
probe v10-path "ingestion: POST /v10/probe: (b) path" "$(printf "$NEWOP" ingest)"
probe reporting-principal "ingestion: POST /v10/probe: (b) x-principal is 'reporting'" "$(printf "$NEWOP" reporting)"
probe marked-without-row "ingestion: POST /v1/transactions: (c) marked operation has no row" "v = v.replace('ingestion\tPOST\t/v1/transactions\n', '', 1)"
probe row-without-marker "ingestion: POST /v1/transactions: (c) row of principal-also-operations.tsv has no marked operation" "t = t.replace('$MARK\n', '', 1)"
probe row-without-operation "ingestion: POST /v1/nothing: (c) row of principal-also-operations.tsv has no marked operation" "v += 'ingestion\tPOST\t/v1/nothing\n'"
probe duplicate-row "principal-also-operations.tsv: line 3 is a duplicate row" "v = v.replace('ingestion\tPOST\t/v1/transactions\n', 'ingestion\tPOST\t/v1/transactions\ningestion\tPOST\t/v1/transactions\n', 1)"
probe header-changed "principal-also-operations.tsv: the header line must be directory	method	path" "v = v.replace('directory\tmethod\tpath', 'directory\tverb\tpath', 1)"
probe row-width "principal-also-operations.tsv: line 2 has 2 fields, expected 3" "v = v.replace('ingestion\tPOST\t/v1/transactions', 'ingestion\tPOST', 1)"

[ "$failed" -eq 0 ] && echo "gst-ci: INV-AC-9149 check-principal-also.py self-test passed."
exit $failed
