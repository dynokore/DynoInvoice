#!/usr/bin/env sh
# spectral/self-test-fixtures.sh — INV-T-2849 (T5)
#
# Proves the breaking-change gate itself works, independent of whether any
# real merge request happens to touch a contract: oasdiff must fail on the
# fixture that removes a response field, and must pass an additive one;
# check-payload-compat.py the same, for an event payload schema. Run once,
# from an image that has both `oasdiff` and `python3` (ci/.gitlab-ci.yml's
# oasdiff-breaking-gate job; asyncapi diff's own payload-diff gap is exactly
# why check-payload-compat.py exists, so its fixture is tested here too rather
# than needing the asyncapi/cli image as well).
#
# INV-T-2850 follow-up (T6): also proves strip-scaffold-placeholder.py's
# narrow exemption, the same way — removing ONLY the T5 scaffold placeholder
# must pass, removing a real operation must still fail (CS-50), and a
# scaffold-shaped marker appearing only in HEAD must earn no exemption for a
# real operation's removal (the rule only ever reads BASE).
set -u
cd "$(dirname "$0")"
failed=0

echo "gst-ci: self-test — oasdiff must FAIL on a removed response field"
if oasdiff breaking fixtures/openapi-before.yaml fixtures/openapi-after-breaking.yaml --fail-on ERR; then
  echo "gst-ci: FAIL — oasdiff did not detect the breaking fixture"
  failed=1
fi

echo "gst-ci: self-test — oasdiff must PASS an additive-only change"
if ! oasdiff breaking fixtures/openapi-before.yaml fixtures/openapi-after-additive.yaml --fail-on ERR; then
  echo "gst-ci: FAIL — oasdiff flagged an additive-only change as breaking"
  failed=1
fi

echo "gst-ci: self-test — check-payload-compat.py must FAIL on a removed field"
if python3 check-payload-compat.py fixtures/payload-before.schema.json fixtures/payload-after-breaking.schema.json; then
  echo "gst-ci: FAIL — check-payload-compat.py did not detect the breaking fixture"
  failed=1
fi

echo "gst-ci: self-test — check-payload-compat.py must PASS an additive-only change"
if ! python3 check-payload-compat.py fixtures/payload-before.schema.json fixtures/payload-after-additive.schema.json; then
  echo "gst-ci: FAIL — check-payload-compat.py flagged an additive-only change as breaking"
  failed=1
fi

echo "gst-ci: self-test — strip-scaffold-placeholder.py + oasdiff must PASS when only the T5 scaffold placeholder is removed"
cp fixtures/scaffold-placeholder-base.yaml /tmp/scaffold-base-1.yaml
python3 strip-scaffold-placeholder.py widget /tmp/scaffold-base-1.yaml
if ! oasdiff breaking /tmp/scaffold-base-1.yaml fixtures/scaffold-placeholder-head.yaml --fail-on ERR; then
  echo "gst-ci: FAIL — removing only the T5 scaffold placeholder was flagged as breaking"
  failed=1
fi

echo "gst-ci: self-test — removal of a real (non-placeholder) operation must still FAIL (CS-50)"
cp fixtures/scaffold-placeholder-head.yaml /tmp/scaffold-base-2.yaml
python3 strip-scaffold-placeholder.py widget /tmp/scaffold-base-2.yaml   # no placeholder in this base: must be a no-op
if oasdiff breaking /tmp/scaffold-base-2.yaml fixtures/real-operation-removed.yaml --fail-on ERR; then
  echo "gst-ci: FAIL — removing a real operation was not detected as breaking"
  failed=1
fi

echo "gst-ci: self-test — the scaffold marker appearing only in HEAD must give no exemption"
cp fixtures/scaffold-placeholder-head.yaml /tmp/scaffold-base-3.yaml
python3 strip-scaffold-placeholder.py widget /tmp/scaffold-base-3.yaml   # base still has no placeholder: must be a no-op
if oasdiff breaking /tmp/scaffold-base-3.yaml fixtures/scaffold-marker-head-only.yaml --fail-on ERR; then
  echo "gst-ci: FAIL — a real operation's removal was exempted because HEAD (not BASE) carries the scaffold marker"
  failed=1
fi
rm -f /tmp/scaffold-base-1.yaml /tmp/scaffold-base-2.yaml /tmp/scaffold-base-3.yaml

# INV-T-3491 gate fix (INV-Q-3569): normalize-extensible-enum.py and oasdiff-exceptions.txt.
gate() { # base head [extra oasdiff args]: normalise both as run-oasdiff-gate.sh does, then diff
  python3 normalize-extensible-enum.py "$1" /tmp/gate-base.yaml
  python3 normalize-extensible-enum.py "$2" /tmp/gate-head.yaml
  shift 2
  oasdiff breaking /tmp/gate-base.yaml /tmp/gate-head.yaml --fail-on ERR "$@"
}
echo "gst-ci: self-test — a value added to an API-09 extensible response enum must PASS"
if ! gate fixtures/enum-marked-before.yaml fixtures/enum-marked-added.yaml; then
  echo "gst-ci: FAIL — a value added to an x-extensible-enum response enum was flagged as breaking"; failed=1
fi
echo "gst-ci: self-test — a value added to an unmarked response enum must still FAIL"
if gate fixtures/enum-closed-before.yaml fixtures/enum-closed-added.yaml; then
  echo "gst-ci: FAIL — a value added to an unmarked enum was accepted"; failed=1
fi
echo "gst-ci: self-test — a new request maxItems must FAIL without a recorded exception"
if gate fixtures/enum-marked-before.yaml fixtures/maxitems-set.yaml; then
  echo "gst-ci: FAIL — a new request bound was accepted with no exception"; failed=1
fi
echo "gst-ci: self-test — the same change must PASS with its one recorded exception"
if ! gate fixtures/enum-marked-before.yaml fixtures/maxitems-set.yaml --err-ignore fixtures/oasdiff-exceptions-fixture.txt; then
  echo "gst-ci: FAIL — a recorded exception did not admit its change"; failed=1
fi
rm -f /tmp/gate-base.yaml /tmp/gate-head.yaml

# INV-T-5469 (lesson P243): a payload schema file new in the merge request is checked against
# API-41 (root additionalProperties never false). Each case runs run-oasdiff-gate.sh in a
# throwaway git repository: a base commit holding widget/schemas/base-payload.schema.json,
# then one commit per case on top of it.
SPECTRAL_DIR=$(pwd)
NEWFILE_TMP=$(mktemp -d)
trap 'rm -rf "$NEWFILE_TMP"' EXIT
trap 'rm -rf "$NEWFILE_TMP"; exit 1' HUP INT TERM
mkdir -p "$NEWFILE_TMP/spectral" "$NEWFILE_TMP/widget/schemas"
cp run-oasdiff-gate.sh check-payload-compat.py "$NEWFILE_TMP/spectral/"
cp fixtures/payload-before.schema.json "$NEWFILE_TMP/widget/schemas/base-payload.schema.json"
tgit() { git -C "$NEWFILE_TMP" -c user.name=gst-ci -c user.email=gst-ci@invoice.local "$@"; }
tgit init -q
tgit add -A
tgit commit -q -m base
NEWFILE_BASE=$(tgit rev-parse HEAD)
gate_out=""
gate_rc=0
# run_case: reset to the base commit, apply "$@" (a shell function name), commit, run the gate.
run_case() {
  tgit reset -q --hard "$NEWFILE_BASE"
  tgit clean -fdq
  "$@"
  tgit add -A
  tgit commit -q --allow-empty -m case
  gate_rc=0
  gate_out=$(cd "$NEWFILE_TMP" && sh spectral/run-oasdiff-gate.sh "$NEWFILE_BASE" 2>&1) || gate_rc=$?
}
add_file() { # dest source: copy a fixture to dest (relative to the throwaway repo)
  mkdir -p "$NEWFILE_TMP/$(dirname "$1")"
  cp "$SPECTRAL_DIR/$2" "$NEWFILE_TMP/$1"
}
add_closed() { add_file "$1" fixtures/payload-new-closed.schema.json; }
close_base() { sed 's/"additionalProperties": true/"additionalProperties": false/' "$SPECTRAL_DIR/fixtures/payload-before.schema.json" > "$NEWFILE_TMP/widget/schemas/base-payload.schema.json"; }
add_nested_closed() {
  printf '%s\n' '{"type":"object","properties":{"a":{"type":"object","additionalProperties":false}},"additionalProperties":true}' > "$NEWFILE_TMP/widget/schemas/new-nested-payload.schema.json"
}

echo "gst-ci: self-test — a new closed payload schema (additionalProperties: false) must FAIL the gate"
run_case add_closed widget/schemas/new-closed-payload.schema.json
if [ "$gate_rc" -eq 0 ] || ! printf '%s\n' "$gate_out" | grep -F "widget/schemas/new-closed-payload.schema.json" | grep -qF "additionalProperties: false"; then
  echo "gst-ci: FAIL — a new payload schema with additionalProperties: false was not rejected"; failed=1
fi

echo "gst-ci: self-test — a new open payload schema with required properties must PASS and be checked"
run_case add_file widget/schemas/new-open-payload.schema.json fixtures/payload-new-open-required.schema.json
if [ "$gate_rc" -ne 0 ] || ! printf '%s\n' "$gate_out" | grep -qF "payload new-file check widget/schemas/new-open-payload.schema.json"; then
  echo "gst-ci: FAIL — a new open payload schema was rejected or not checked"; failed=1
fi

echo "gst-ci: self-test — look-alikes of the excluded envelope names must FAIL when closed"
for p in widget/schemas/error-envelope.schema.json widget/schemas/cloud-event-envelope.schema.json \
         service-runtime/schemas/error-envelope2.schema.json service-runtime/schemas/my-cloud-event-envelope.schema.json; do
  run_case add_closed "$p"
  if [ "$gate_rc" -eq 0 ]; then
    echo "gst-ci: FAIL — closed look-alike $p was exempted like the shared envelope"; failed=1
  fi
done

echo "gst-ci: self-test — the two exact shared envelope paths must PASS when closed"
for p in service-runtime/schemas/error-envelope.schema.json service-runtime/schemas/cloud-event-envelope.schema.json; do
  run_case add_closed "$p"
  if [ "$gate_rc" -ne 0 ]; then
    echo "gst-ci: FAIL — the excluded shared envelope path $p was checked"; failed=1
  fi
done

echo "gst-ci: self-test — a closed file outside the */schemas/*.schema.json glob must PASS"
for p in widget/schemas/x.json widget/schemas/x.schema.json.bak widget/x.schema.json; do
  run_case add_closed "$p"
  if [ "$gate_rc" -ne 0 ]; then
    echo "gst-ci: FAIL — $p is outside the schema glob but failed the gate"; failed=1
  fi
done

echo "gst-ci: self-test — a base payload schema changed to a closed root must FAIL (unchanged rule)"
run_case close_base
if [ "$gate_rc" -eq 0 ]; then
  echo "gst-ci: FAIL — a base file changed to additionalProperties: false was accepted"; failed=1
fi
echo "gst-ci: self-test — an unchanged base payload schema must PASS"
run_case true
if [ "$gate_rc" -ne 0 ]; then
  echo "gst-ci: FAIL — an unchanged base file failed the gate"; failed=1
fi

echo "gst-ci: self-test — a new file closed only below the root must PASS (root-only rule)"
run_case add_nested_closed
if [ "$gate_rc" -ne 0 ]; then
  echo "gst-ci: FAIL — a nested additionalProperties: false failed the new-file check"; failed=1
fi
rm -rf "$NEWFILE_TMP"

# INV-T-5648: nested, array-item and local-$ref comparison (API-41)
NB=fixtures/payload-nested-before.schema.json
NESTED_TMP=$(mktemp -d)
trap 'rm -rf "$NESTED_TMP"' EXIT
# edit <name> <python statement on d>: writes a modified copy of the nested before fixture
edit() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); exec(sys.argv[3]); json.dump(d,open(sys.argv[2],"w"))' "$NB" "$NESTED_TMP/$1.json" "$2"; }

echo "gst-ci: self-test — a nested property made required must FAIL (inner.required gains b)"
nested_out=$(python3 check-payload-compat.py "$NB" fixtures/payload-nested-after-required.schema.json); nested_rc=$?
if [ "$nested_rc" -ne 1 ] || ! printf '%s\n' "$nested_out" | grep -F "made required" | grep -F "at 'inner'" | grep -qF "['b']"; then
  echo "gst-ci: FAIL — a nested made-required name was not reported"; failed=1
fi

echo "gst-ci: self-test — nested optional additions (incl. a property named required) must PASS"
nested_out=$(python3 check-payload-compat.py "$NB" fixtures/payload-nested-after-optional.schema.json); nested_rc=$?
if [ "$nested_rc" -ne 0 ] || ! printf '%s\n' "$nested_out" | grep -qF "additive-only" || printf '%s\n' "$nested_out" | grep -qF "retyped"; then
  echo "gst-ci: FAIL — nested optional additions were rejected"; failed=1
fi

echo "gst-ci: self-test — a name made required in a local \$ref target must FAIL"
edit refreq 'd["$defs"]["party"]["required"]=["name","id"]'
nested_out=$(python3 check-payload-compat.py "$NB" "$NESTED_TMP/refreq.json"); nested_rc=$?
if [ "$nested_rc" -ne 1 ] || ! printf '%s\n' "$nested_out" | grep -F "at 'party'" | grep -qF "['id']"; then
  echo "gst-ci: FAIL — a made-required name behind a local \$ref was not reported"; failed=1
fi

echo "gst-ci: self-test — a name made required in array items must FAIL"
edit itemreq 'd["properties"]["lines"]["items"]["required"]=["sku","qty"]'
nested_out=$(python3 check-payload-compat.py "$NB" "$NESTED_TMP/itemreq.json"); nested_rc=$?
if [ "$nested_rc" -ne 1 ] || ! printf '%s\n' "$nested_out" | grep -qF "at 'lines[]'"; then
  echo "gst-ci: FAIL — a made-required name in array items was not reported"; failed=1
fi

echo "gst-ci: self-test — a nested removed property must FAIL"
edit rm 'del d["properties"]["inner"]["properties"]["b"]'
nested_out=$(python3 check-payload-compat.py "$NB" "$NESTED_TMP/rm.json"); nested_rc=$?
if [ "$nested_rc" -ne 1 ] || ! printf '%s\n' "$nested_out" | grep -qF "removed property 'b' at 'inner'"; then
  echo "gst-ci: FAIL — a nested removed property was not reported"; failed=1
fi

echo "gst-ci: self-test — a nested retyped property must FAIL"
edit retype 'd["properties"]["inner"]["properties"]["a"]["type"]="integer"'
nested_out=$(python3 check-payload-compat.py "$NB" "$NESTED_TMP/retype.json"); nested_rc=$?
if [ "$nested_rc" -ne 1 ] || ! printf '%s\n' "$nested_out" | grep -qF "retyped property 'a' at 'inner': string -> integer"; then
  echo "gst-ci: FAIL — a nested retyped property was not reported"; failed=1
fi

echo "gst-ci: self-test — the nested fixture against itself must PASS"
nested_out=$(python3 check-payload-compat.py "$NB" "$NB"); nested_rc=$?
if [ "$nested_rc" -ne 0 ] || printf '%s\n' "$nested_out" | grep -qF "retyped"; then
  echo "gst-ci: FAIL — the nested fixture failed against itself"; failed=1
fi

echo "gst-ci: self-test — a retyped items type must FAIL"
edit itemtype 'd["properties"]["tags"]["items"]["type"]="integer"'
nested_out=$(python3 check-payload-compat.py "$NB" "$NESTED_TMP/itemtype.json"); nested_rc=$?
if [ "$nested_rc" -ne 1 ] || ! printf '%s\n' "$nested_out" | grep -qF "retyped items at 'tags[]': string -> integer"; then
  echo "gst-ci: FAIL — a retyped items type was not reported"; failed=1
fi

echo "gst-ci: self-test — a property retyped inside array items must FAIL"
edit itemprop 'd["properties"]["lines"]["items"]["properties"]["qty"]["type"]="integer"'
nested_out=$(python3 check-payload-compat.py "$NB" "$NESTED_TMP/itemprop.json"); nested_rc=$?
if [ "$nested_rc" -ne 1 ] || ! printf '%s\n' "$nested_out" | grep -qF "retyped property 'qty' at 'lines[]': string -> integer"; then
  echo "gst-ci: FAIL — a property retyped inside array items was not reported"; failed=1
fi

echo "gst-ci: self-test — a changed file \$ref must FAIL"
edit fileref 'd["properties"]["state"]["$ref"]="./other-states.schema.json#/$defs/lifecycleState"'
nested_out=$(python3 check-payload-compat.py "$NB" "$NESTED_TMP/fileref.json"); nested_rc=$?
if [ "$nested_rc" -ne 1 ] || ! printf '%s\n' "$nested_out" | grep -qF "changed \$ref at 'state'"; then
  echo "gst-ci: FAIL — a changed file \$ref was not reported"; failed=1
fi

echo "gst-ci: self-test — an unresolved local \$ref in the new file must FAIL"
edit missing 'd["properties"]["party"]["$ref"]="#/$defs/missing"'
nested_out=$(python3 check-payload-compat.py "$NB" "$NESTED_TMP/missing.json"); nested_rc=$?
if [ "$nested_rc" -ne 1 ] || ! printf '%s\n' "$nested_out" | grep -qF "unresolved \$ref '#/\$defs/missing' at 'party'"; then
  echo "gst-ci: FAIL — an unresolved local \$ref was not reported"; failed=1
fi

echo "gst-ci: self-test — a recursive schema against an identical copy must PASS and end"
edit rec1 'd["properties"]={"node":{"$ref":"#/$defs/node"}}; d["required"]=[]; d["$defs"]={"node":{"type":"object","properties":{"child":{"$ref":"#/$defs/node"}}}}'
cp "$NESTED_TMP/rec1.json" "$NESTED_TMP/rec2.json"
if ! timeout 20 python3 check-payload-compat.py "$NESTED_TMP/rec1.json" "$NESTED_TMP/rec2.json" >/dev/null; then
  echo "gst-ci: FAIL — a recursive schema did not pass within 20 seconds"; failed=1
fi
rm -rf "$NESTED_TMP"
trap - EXIT

if [ "$failed" -eq 0 ]; then
  echo "gst-ci: breaking-change gate self-test passed (fails on breaking, passes on additive, exempts only the exact BASE-carried T5 scaffold placeholder, admits only marked extensible-enum values and recorded exceptions, rejects a new payload schema file with a closed root additionalProperties, and compares nested properties, array items and local $ref targets, file $ref values and recursive schemas)."
fi
exit $failed
