#!/usr/bin/env sh
# spectral/self-test-flow-context-rule.sh — INV-T-17619 (slice 1 of INV-T-9256, INV-AC-17615; lesson P20).
# Proves the Spectral rule inv-flow-context-needs-sync-edge fires (exit 1, the rule's code) on an
# operation with x-flow-context and no x-sync-edge, and stays quiet (exit 0, no hit of the rule) on an
# operation with x-sync-edge, with or without the marker.
# Run from an image with the `spectral` binary ($SPECTRAL_IMAGE).
set -u
cd "$(dirname "$0")"
failed=0
rule=inv-flow-context-needs-sync-edge
# hits_of <file>: sets RC (spectral exit status) and HITS (one line per hit of the rule: its path)
hits_of() {
  out=$(spectral lint --ruleset ruleset.yaml --fail-severity error -f json "$1" 2>/dev/null); RC=$?
  HITS=$(printf '%s' "$out" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const seen=new Set();for(const r of JSON.parse(s))if(r.code===process.argv[1])seen.add(r.path.join("."));console.log([...seen].sort().join("\n"))})' "$rule")
}
hits_of fixtures/flow-context-accepted.yaml
if [ "$RC" -eq 0 ] && [ -z "$HITS" ]; then echo "gst-ci: self-test flow-context-accepted accepted by $rule as expected"
else echo "gst-ci: FAIL — flow-context-accepted: expected exit 0 and no hit of $rule (rc=$RC):"; echo "$HITS"; failed=1; fi
hits_of fixtures/flow-context-refused.yaml
if [ "$RC" -eq 1 ] && [ "$HITS" = "paths./internal/v1/fixture/marked.get" ]; then echo "gst-ci: self-test flow-context-refused refused by $rule as expected"
else echo "gst-ci: FAIL — flow-context-refused: expected exit 1 and one hit of $rule on the marked operation (rc=$RC):"; echo "$HITS"; failed=1; fi
[ "$failed" -eq 0 ] && echo "gst-ci: $rule self-test passed."
exit $failed
