#!/usr/bin/env sh
# spectral/self-test-machine-route-keys.sh — INV-T-13571, acceptance criterion INV-AC-13563 (AUTHZ-01, lesson P20).
# Proves spectral/check-machine-route-keys.py fires: the clean tree exits 0; each probe is a copy of the four input
# files changed in one place (nothing is committed) and must make the checker exit 1 with the finding named.
# Needs python3 + PyYAML.
set -u
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
failed=0
FILES="route-scopes/machine-routes.tsv route-scopes/machine-route-keys.tsv access/schemas/machine-principal-permissions.tsv catalogue/role-permissions/role-permissions.yaml"
PORT_ROUTE='get\t/v1/portfolio\treporting\t-\n'
PORT_KEY='get\t/v1/portfolio\tperiod-readiness.view\t-\tperson\n'

fresh() { for f in $FILES; do mkdir -p "$TMP/$1/$(dirname "$f")"; cp "$f" "$TMP/$1/$f"; done; }
mutate() { # name, file, python statements on t (the file text)
  python3 -I - "$TMP/$1/$2" "$3" <<'PY'
import sys
path, code = sys.argv[1], sys.argv[2]
t = open(path).read()
before = t
exec(code)
assert t != before, "probe changed nothing"
open(path, "w").write(t)
PY
}
expect() { # name, expected fragment (checker run on $TMP/name)
  out=$(python3 spectral/check-machine-route-keys.py "$TMP/$1" 2>&1); rc=$?
  if [ $rc -ne 1 ]; then echo "gst-ci: FAIL — INV-AC-13563 probe '$1' exited $rc, expected 1"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: INV-AC-13563 probe '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — INV-AC-13563 probe '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
probe() { # name, expected fragment, file, python code
  fresh "$1"
  if ! mutate "$1" "$3" "$4"; then echo "gst-ci: FAIL — INV-AC-13563 probe '$1' could not be built"; failed=1; return; fi
  expect "$1" "$2"
}
R=route-scopes/machine-routes.tsv
K=route-scopes/machine-route-keys.tsv
P=access/schemas/machine-principal-permissions.tsv

fresh clean
out=$(python3 spectral/check-machine-route-keys.py "$TMP/clean" 2>&1); rc=$?
case "$out" in "machine-route-keys-check: "*" routes keyed") [ $rc -eq 0 ] && echo "gst-ci: INV-AC-13563 clean tree passed as expected ($out)" || { echo "gst-ci: FAIL — clean tree exit $rc"; failed=1; };;
  *) echo "gst-ci: FAIL — INV-AC-13563 clean tree: exit $rc, '$out'"; failed=1;; esac

# route <-> key rows
fresh portfolio-in-both
mutate portfolio-in-both $R "t += '$PORT_ROUTE'"
mutate portfolio-in-both $K "t += '$PORT_KEY'"
out=$(python3 spectral/check-machine-route-keys.py "$TMP/portfolio-in-both" 2>&1); rc=$?
if [ $rc -eq 0 ] && [ "$out" = "machine-route-keys-check: 22 routes keyed" ]; then echo "gst-ci: INV-AC-13563 both tables with get /v1/portfolio passed as expected ($out)"; else echo "gst-ci: FAIL — both tables with portfolio: exit $rc $out"; failed=1; fi
probe route-without-key "get /v1/portfolio: (a) route has no row" $R "t += '$PORT_ROUTE'"
probe key-without-route "get /v1/portfolio: (a) key row has no route" $K "t += '$PORT_KEY'"
probe key-removed "post /v1/aggregates: (a) route has no row" $K "t = t.replace('post\t/v1/aggregates\t', 'post\t/v1/aggregates-x\t', 1)"
probe duplicate-key-row "(a) duplicate row in machine-route-keys.tsv" $K "t += 'get\t/v1/exceptions\texception.view\tmachine.status.read\tperson\n'"
# decided_by
probe decided-by-other "post /v1/aggregates: (b) decided_by 'service'" $K "t = t.replace('machine.certification.ingest\tmachine\n', 'machine.certification.ingest\tservice\n', 1)"
probe decided-by-capital "post /v1/aggregates: (b) decided_by 'Machine'" $K "t = t.replace('machine.certification.ingest\tmachine\n', 'machine.certification.ingest\tMachine\n', 1)"
probe decided-by-empty "post /v1/aggregates: (b) decided_by ''" $K "t = t.replace('machine.certification.ingest\tmachine\n', 'machine.certification.ingest\t\n', 1)"
# machine rows
probe machine-person-key "post /v1/certification-reports: (c) machine row permission 'period-readiness.view' does not start" $K "t = t.replace('machine.certification.report.record', 'period-readiness.view', 1)"
probe machine-no-prefix "(c) machine row permission 'drain.run' does not start" $K "t = t.replace('machine.drain.run', 'drain.run', 1)"
probe machine-trailing-space "(c) permission 'machine.drain.run ' is not machine." $K "t = t.replace('machine.drain.run', 'machine.drain.run ', 1)"
probe machine-capital "(c) machine row permission 'Machine.drain.run' does not start" $K "t = t.replace('machine.drain.run', 'Machine.drain.run', 1)"
probe machine-double-prefix "(c) permission 'machine.machine.drain.run' is not machine." $K "t = t.replace('machine.drain.run', 'machine.machine.drain.run', 1)"
probe key-of-other-type "post /v1/entities/{entityRef}/periods/{periodRef}/drain: (c) permission 'machine.status.read' is not machine. + a permission of the scope type 'filing-workflow'" $K "t = t.replace('machine.drain.run', 'machine.status.read', 1)"
probe key-of-unknown-permission "(c) permission 'machine.drain.unknown' is not" $K "t = t.replace('machine.drain.run', 'machine.drain.unknown', 1)"
probe scope-capital "(c) permission 'machine.drain.run' is not machine. + a permission of the scope type 'Filing-Workflow'" $R "t = t.replace('filing-workflow', 'Filing-Workflow', 1)"
probe also-on-route-without-also-scope "post /v1/certification-reports: (c) permission_also 'machine.certification.ingest' on a route whose also_scope is '-'" $K "t = t.replace('machine.certification.report.record\t-', 'machine.certification.report.record\tmachine.certification.ingest', 1)"
probe also-missing "post /v1/aggregates: (c) permission_also is '-' but also_scope is 'certification'" $K "t = t.replace('post\t/v1/aggregates\tmachine.ingestion.channels.write\tmachine.certification.ingest', 'post\t/v1/aggregates\tmachine.ingestion.channels.write\t-', 1)"
probe also-not-held "post /v1/aggregates: (c) permission_also 'machine.status.read' is not held by the also_scope type 'certification'" $K "t = t.replace('post\t/v1/aggregates\tmachine.ingestion.channels.write\tmachine.certification.ingest', 'post\t/v1/aggregates\tmachine.ingestion.channels.write\tmachine.status.read', 1)"
probe also-trailing-space "post /v1/aggregates: (c) permission_also 'machine.certification.ingest ' is not held" $K "t = t.replace('post\t/v1/aggregates\tmachine.ingestion.channels.write\tmachine.certification.ingest', 'post\t/v1/aggregates\tmachine.ingestion.channels.write\tmachine.certification.ingest ', 1)"
probe also-capital "post /v1/aggregates: (c) permission_also 'Machine.certification.ingest' is not held" $K "t = t.replace('post\t/v1/aggregates\tmachine.ingestion.channels.write\tmachine.certification.ingest', 'post\t/v1/aggregates\tmachine.ingestion.channels.write\tMachine.certification.ingest', 1)"
probe permissions-row-removed "post /v1/entities/{entityRef}/periods/{periodRef}/drain: (c) permission 'machine.drain.run' is not" $P "t = t.replace('FILING_WORKFLOW\tdrain.run\tINV-REQ-584 behavior 1\n', '', 1)"
# person rows
probe person-machine-key "get /v1/exceptions: (d) person row permission 'machine.status.read' starts with" $K "t = t.replace('exception.view\tmachine.status.read', 'machine.status.read\tmachine.status.read', 1)"
probe person-not-in-catalogue "get /v1/exceptions: (d) person row permission 'exception.viewx' is not a key" $K "t = t.replace('exception.view\t', 'exception.viewx\t', 1)"
probe person-trailing-space "get /v1/exceptions: (d) person row permission 'exception.view ' is not a key" $K "t = t.replace('exception.view\t', 'exception.view \t', 1)"
probe person-capital "get /v1/exceptions: (d) person row permission 'Exception.view' is not a key" $K "t = t.replace('exception.view\t', 'Exception.view\t', 1)"
probe person-also-not-held "get /v1/exceptions: (d) permission_also 'machine.certification.ingest' is not a machine key held by the scope type 'reporting'" $K "t = t.replace('exception.view\tmachine.status.read', 'exception.view\tmachine.certification.ingest', 1)"
probe person-also-missing "get /v1/exceptions: (d) permission_also '-' differs from the pinned 'machine.status.read'" $K "t = t.replace('exception.view\tmachine.status.read', 'exception.view\t-', 1)"
probe person-also-other-held "get /v1/exceptions: (d) permission_also 'machine.reconciliation.read' differs from the pinned" $K "t = t.replace('exception.view\tmachine.status.read', 'exception.view\tmachine.reconciliation.read', 1)"
probe exceptions-as-machine "get /v1/exceptions: (d) a person route must have decided_by person" $K "t = t.replace('exception.view\tmachine.status.read\tperson', 'machine.status.read\t-\tmachine', 1)"
probe machine-row-person-key "get /v1/compliance-metrics: (c) machine row permission 'exception.view' does not start" $K "t = t.replace('get\t/v1/compliance-metrics\tmachine.status.read', 'get\t/v1/compliance-metrics\texception.view', 1)"
probe compliance-metrics-as-person "get /v1/compliance-metrics: (d) person row, but the route is not in the person-route allowlist" $K "t = t.replace('get\t/v1/compliance-metrics\tmachine.status.read\t-\tmachine', 'get\t/v1/compliance-metrics\texception.view\t-\tperson', 1)"
# portfolio: any permission_also fails
for also in machine.status.read machine.certification.ingest; do
  fresh "portfolio-also-$also"
  mutate "portfolio-also-$also" $R "t += '$PORT_ROUTE'"
  mutate "portfolio-also-$also" $K "t += 'get\t/v1/portfolio\tperiod-readiness.view\t$also\tperson\n'"
  expect "portfolio-also-$also" "get /v1/portfolio: (d) permission_also"
done
fresh portfolio-machine-key
mutate portfolio-machine-key $R "t += '$PORT_ROUTE'"
mutate portfolio-machine-key $K "t += 'get\t/v1/portfolio\tmachine.status.read\t-\tperson\n'"
expect portfolio-machine-key "get /v1/portfolio: (d) person row permission 'machine.status.read' starts with"
# look-alike: same file name in another directory (the real key table is absent from route-scopes/)
fresh other-directory
mkdir -p "$TMP/other-directory/elsewhere"
mv "$TMP/other-directory/$K" "$TMP/other-directory/elsewhere/machine-route-keys.tsv"
expect other-directory "machine-route-keys.tsv: unreadable"
# header / width
probe header-changed "machine-route-keys.tsv: the header line must be method" $K "t = t.replace('decided_by', 'decider', 1)"
probe row-width "machine-route-keys.tsv: line 2 has 4 fields, expected 5" $K "t = t.replace('machine.certification.ingest\tmachine\n', 'machine.certification.ingest\n', 1)"

[ "$failed" -eq 0 ] && echo "gst-ci: INV-AC-13563 check-machine-route-keys.py self-test passed."
exit $failed
