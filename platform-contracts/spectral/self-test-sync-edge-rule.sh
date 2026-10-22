#!/usr/bin/env sh
# spectral/self-test-sync-edge-rule.sh — INV-T-2850 follow-up (API-08, lesson P20).
# Proves the Spectral rule inv-api-08-x-sync-edge fires on an /internal/v1
# operation without x-sync-edge or with a malformed one, and stays quiet on a
# compliant internal operation and on a non-internal operation. (The rule had
# never fired before: it selected only operations that already had the field.)
# Run from an image with the `spectral` binary ($SPECTRAL_IMAGE).
set -u
cd "$(dirname "$0")"
failed=0
lint() { spectral lint --ruleset ruleset.yaml --fail-severity error "$1" 2>&1; }
for fx in api08-missing api08-malformed; do
  out=$(lint fixtures/$fx.yaml)
  case "$out" in *inv-api-08-x-sync-edge*) echo "gst-ci: self-test $fx failed on inv-api-08-x-sync-edge as expected";;
    *) echo "gst-ci: FAIL — $fx did not trigger inv-api-08-x-sync-edge:"; echo "$out"; failed=1;; esac
done
for fx in api08-ok api08-shared-star; do
  out=$(lint fixtures/$fx.yaml)
  case "$out" in *inv-api-08-x-sync-edge*) echo "gst-ci: FAIL — $fx wrongly triggered inv-api-08-x-sync-edge:"; echo "$out"; failed=1;;
    *) echo "gst-ci: self-test $fx passes inv-api-08-x-sync-edge as expected";; esac
done

# INV-T-8456 (INV-AC-8455): x-sync-edge as a string, a sequence or the exclusion form. A
# case is asserted by exit status and by the rule's message, compared by equality.
# hits_of <file> <rule code> sets RC (spectral exit status) and HITS ("<path><TAB><message>" lines).
hits_of() {
  out=$(spectral lint --ruleset ruleset.yaml --fail-severity error -f json "$1" 2>/dev/null); RC=$?
  HITS=$(printf '%s' "$out" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const seen=new Set();for(const r of JSON.parse(s))if(r.code===process.argv[1])seen.add(r.path.join(".")+"\t"+r.message);console.log([...seen].sort().join("\n"))})' "$2")
}
# expect_refused <fixture> <rule code> <message>: exit status 1 and every hit of the rule is the message, on x-sync-edge of the operation
expect_refused() {
  hits_of "fixtures/$1.yaml" "$2"
  # sequence elements are reported on x-sync-edge.<n>; accept that path suffix with the same message
  wrong=$(printf '%s\n' "$HITS" | grep . | grep -vE "^paths\./internal/v1/fixture/op\.get\.x-sync-edge(\.[0-9]+)?	" | wc -l)
  other=$(printf '%s\n' "$HITS" | grep . | cut -f2 | grep -vcxF "$3")
  if [ "$RC" -eq 1 ] && [ -n "$HITS" ] && [ "$wrong" -eq 0 ] && [ "$other" -eq 0 ]; then echo "gst-ci: self-test $1 refused by $2 as expected"
  else echo "gst-ci: FAIL — $1: expected exit 1 and only the message of $2 on x-sync-edge (rc=$RC):"; echo "$HITS"; failed=1; fi
}
# expect_accepted <fixture> <rule code>: exit status 0 and no hit of the rule
expect_accepted() {
  hits_of "fixtures/$1.yaml" "$2"
  if [ "$RC" -eq 0 ] && [ -z "$HITS" ]; then echo "gst-ci: self-test $1 accepted by $2 as expected"
  else echo "gst-ci: FAIL — $1: expected exit 0 and no hit of $2 (rc=$RC):"; echo "$HITS"; failed=1; fi
}
rule=inv-api-08-x-sync-edge
msg="internal operation must carry x-sync-edge: a '<caller>-><callee>' string, a sequence of at least two such strings of one callee, or 'any-except:<svc>-><callee>' (API-08)"
expect_accepted inv-ac-8455-api08-ok-sequence $rule
expect_accepted inv-ac-8455-api08-ok-exclusion $rule
expect_accepted inv-ac-8455-api08-ok-star $rule
expect_refused inv-ac-8455-api08-bad-one $rule "$msg"
expect_refused inv-ac-8455-api08-bad-empty $rule "$msg"
expect_refused inv-ac-8455-api08-bad-mapping $rule "$msg"
expect_refused inv-ac-8455-api08-bad-number $rule "$msg"
expect_refused inv-ac-8455-api08-bad-non-string $rule "$msg"
expect_refused inv-ac-8455-api08-bad-two-callees $rule "$msg"
expect_refused inv-ac-8455-api08-bad-repeat $rule "$msg"
expect_refused inv-ac-8455-api08-bad-any-and-named $rule "$msg"
expect_refused inv-ac-8455-api08-bad-exclusion-in-sequence $rule "$msg"
expect_refused inv-ac-8455-api08-bad-exclusion-list $rule "$msg"
expect_refused inv-ac-8455-api08-bad-exclusion-empty $rule "$msg"
expect_refused inv-ac-8455-api08-bad-exclusion-upper $rule "$msg"
expect_refused inv-ac-8455-api08-bad-exclusion-spaced $rule "$msg"
expect_refused inv-ac-8455-api08-bad-exclusion-non-svc $rule "$msg"
expect_refused inv-ac-8455-api08-bad-exclusion-trailing-space $rule "$msg"
expect_refused inv-ac-8455-api08-bad-star-in-sequence $rule "$msg"
[ "$failed" -eq 0 ] && echo "gst-ci: inv-api-08-x-sync-edge self-test passed."
exit $failed
