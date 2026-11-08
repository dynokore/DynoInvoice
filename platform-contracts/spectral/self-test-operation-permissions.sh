#!/usr/bin/env sh
# spectral/self-test-operation-permissions.sh — INV-T-18670, acceptance criterion INV-AC-18673 (lesson P20).
# Proves spectral/check-operation-permissions.py fires: the clean tree exits 0; each probe is a copy of the tree
# changed in one place (nothing is committed) and must make the checker exit 1 with a line naming the operation.
# Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
failed=0

fresh() { # name -> a copy of the inputs in $TMP/name
  mkdir -p "$TMP/$1/spectral" "$TMP/$1/transport" "$TMP/$1/ingestion" "$TMP/$1/catalogue/role-permissions"
  cp transport/openapi.yaml "$TMP/$1/transport/"
  cp ingestion/openapi.yaml "$TMP/$1/ingestion/"
  cp spectral/operation-permissions.tsv "$TMP/$1/spectral/"
  cp catalogue/role-permissions/role-permissions.yaml "$TMP/$1/catalogue/role-permissions/"
}
mutate() { # name, python statements on t (transport openapi) and v (tsv)
  python3 -I - "$TMP/$1" "$2" <<'PY'
import sys
root, code = sys.argv[1], sys.argv[2]
o, tsv = root + "/transport/openapi.yaml", root + "/spectral/operation-permissions.tsv"
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
  if ! mutate "$1" "$3"; then echo "gst-ci: FAIL — INV-AC-18673 probe '$1' could not be built"; failed=1; return; fi
  out=$(python3 spectral/check-operation-permissions.py "$TMP/$1" 2>&1); rc=$?
  if [ $rc -ne 1 ]; then echo "gst-ci: FAIL — INV-AC-18673 probe '$1' exited $rc, expected 1"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: INV-AC-18673 probe '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — INV-AC-18673 probe '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}

fresh clean
out=$(python3 spectral/check-operation-permissions.py "$TMP/clean" 2>&1); rc=$?
if [ $rc -ne 0 ] || [ "$out" != "operation-permissions-check: 4 rows" ]; then
  echo "gst-ci: FAIL — INV-AC-18673 clean tree: exit $rc, '$out'"; failed=1
else echo "gst-ci: INV-AC-18673 clean tree passed as expected ($out)"; fi

probe lookalike-plural "transport failoverAdapter: (c) permission 'adapter.failovers'" "t = t.replace('permission: adapter.failover\n', 'permission: adapter.failovers\n', 1); v = v.replace('adapter.failover\n', 'adapter.failovers\n', 1)"
probe lookalike-case "transport failoverAdapter: (c) permission 'Adapter.failover'" "t = t.replace('permission: adapter.failover\n', 'permission: Adapter.failover\n', 1); v = v.replace('adapter.failover\n', 'Adapter.failover\n', 1)"
probe key-on-other-operation "transport testConnection: (b) x-operation permission is 'adapter.failover'" "i = t.index('operationId: testConnection'); j = t.index('permission: adapter.configure', i); t = t[:j] + 'permission: adapter.failover' + t[j + len('permission: adapter.configure'):]"
probe kind-caller "transport rotateCredential: (b) x-operation kind is 'CALLER'" "t = t.replace('        kind: PERMISSION\n        permission: ap-credential.manage\n', '        kind: CALLER\n        caller: WORKLOAD\n', 1)"
probe role-property "transport failoverAdapter: (b) x-operation has a role property" "t = t.replace('        permission: adapter.failover\n', '        permission: adapter.failover\n        role: integration.engineer\n', 1)"
probe row-removed "transport failoverAdapter: (d) x-operation carries a permission and the operation has no row" "v = v.replace('transport\tfailoverAdapter\tadapter.failover\n', '', 1)"
probe row-unknown-operation "transport noSuchOperation: (a) 0 operations" "v += 'transport\tnoSuchOperation\tadapter.configure\n'"
probe row-other-directory "ingestion rotateCredential: (a) 0 operations" "v += 'ingestion\trotateCredential\tap-credential.manage\n'"
probe duplicate-row "operation-permissions.tsv: line 6 is a duplicate row for transport rotateCredential" "v += 'transport\trotateCredential\tap-credential.manage\n'"
probe header-changed "operation-permissions.tsv: the header line must be directory	operationId	permission" "v = v.replace('directory\toperationId\tpermission', 'directory\toperation\tpermission', 1)"
probe row-width "operation-permissions.tsv: line 2 has 2 fields, expected 3" "v = v.replace('transport\trotateCredential\tap-credential.manage', 'transport\trotateCredential', 1)"
probe no-data-row "operation-permissions.tsv: the table has no data row" "v = 'directory\toperationId\tpermission\n'"
probe permission-not-in-catalogue "(c) permission 'adapter.nothing'" "t = t.replace('permission: adapter.failover\n', 'permission: adapter.nothing\n', 1); v = v.replace('adapter.failover\n', 'adapter.nothing\n', 1)"

[ "$failed" -eq 0 ] && echo "gst-ci: INV-AC-18673 check-operation-permissions.py self-test passed."
exit $failed
