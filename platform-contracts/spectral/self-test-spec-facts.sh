#!/usr/bin/env sh
# spectral/self-test-spec-facts.sh — INV-T-9614 (lesson P20): proves check-spec-facts.py fires. One passing root
# and one root for each failure of the table check (fixtures/spec-facts/<case>/), then the --base ratchet on a
# throwaway git repository built here and removed at the end.
set -u
cd "$(dirname "$0")"
F=fixtures/spec-facts
failed=0
out=$(python3 check-spec-facts.py $F/pass 2>&1) || { echo "gst-ci: FAIL — compliant fixture rejected:"; echo "$out"; failed=1; }
expect_fail() { # case, finding text
  out=$(python3 check-spec-facts.py "$F/$1" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail no-table "no */spec-facts.tsv found"
expect_fail empty-table "the table has no rows"
expect_fail four-cells "five non-empty cells"
expect_fail six-cells "five non-empty cells"
expect_fail relation-Equals "is not one of equals, contains, absent"
expect_fail relation-equal "is not one of equals, contains, absent"
expect_fail value-not-json "is not valid JSON"
expect_fail absent-with-value "must be '-'"
expect_fail missing-file "does not exist"
expect_fail dir-name-repeated "does not exist"
expect_fail path-leaves-dir "leaves the directory"
expect_fail unparseable-file "cannot be parsed"
expect_fail pointer-no-slash "does not start with '/'"
expect_fail pointer-bad-escape "bad escape"
expect_fail pointer-other-case "the node is missing"
expect_fail missing-node "the node is missing"
expect_fail absent-present "but the row says absent"
expect_fail string-vs-number "the row says 60"
expect_fail number-vs-string "the row says \"60\""
expect_fail one-vs-true "the row says true"
expect_fail true-vs-one "the row says 1"
expect_fail list-order "the row says [\"B\",\"A\"]"
expect_fail contains-not-list "is not a list"
expect_fail contains-missing-member "has no member"
expect_fail duplicate-row "duplicate row"
expect_fail other-service-only "does not exist"
# the finding names the table, the row number, the pointer and the requirement key
out=$(python3 check-spec-facts.py $F/one-vs-true 2>&1)
case "$out" in *"spec-facts.tsv: row 2, pointer /a/one, requirement INV-REQ-1"*) ;; *) echo "gst-ci: FAIL — finding does not name table, row, pointer and requirement:"; echo "$out"; failed=1;; esac

# the ratchet: a throwaway repository with one committed table
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R="$T/repo"; mkdir -p "$R/svc"
cp $F/pass/svc/doc.yaml $F/pass/svc/spec-facts.tsv "$R/svc/"
git -C "$R" init -q && git -C "$R" config user.email t@t && git -C "$R" config user.name t
git -C "$R" add -A && git -C "$R" commit -q -m base
BASE=$(git -C "$R" rev-parse HEAD)
expect_base() { # name, expected rc, finding text (or empty), sha
  out=$(python3 check-spec-facts.py --base "$4" "$R" 2>&1); rc=$?
  if [ "$rc" != "$2" ]; then echo "gst-ci: FAIL — base case '$1' exited $rc, expected $2:"; echo "$out"; failed=1
  elif [ -n "$3" ]; then case "$out" in *"$3"*) echo "gst-ci: self-test base '$1' failed as expected ($3)";; *) echo "gst-ci: FAIL — base case '$1' lacks '$3':"; echo "$out"; failed=1;; esac
  else echo "gst-ci: self-test base '$1' passed as expected"; fi
}
expect_base unchanged 0 "" "$BASE"
# a changed requirement cell, an added row and the header are not findings
sed 's/INV-REQ-1$/INV-REQ-9/' "$R/svc/spec-facts.tsv" > "$T/t" && cp "$T/t" "$R/svc/spec-facts.tsv"
printf 'doc.yaml\t/a/one\tequals\t1\tINV-REQ-2\n' >> "$R/svc/spec-facts.tsv"
expect_base requirement-changed-row-added 0 "" "$BASE"
git -C "$R" checkout -q -- svc/spec-facts.tsv
grep -v '/a/n' "$R/svc/spec-facts.tsv" > "$T/t" && cp "$T/t" "$R/svc/spec-facts.tsv"
expect_base removed-row 1 "row removed since" "$BASE"
git -C "$R" checkout -q -- svc/spec-facts.tsv
sed 's#/a/n\tequals\t60#/a/n\tequals\t61#' "$R/svc/spec-facts.tsv" > "$T/t" && cp "$T/t" "$R/svc/spec-facts.tsv"
sed -i 's/^doc.yaml\t\/a\/n\tequals\t60\t/doc.yaml\t\/a\/n\tequals\t60.0\t/' "$R/svc/spec-facts.tsv"
expect_base changed-value 1 "row removed since" "$BASE"
git -C "$R" checkout -q -- svc/spec-facts.tsv
mv "$R/svc/spec-facts.tsv" "$R/svc/spec-fact.tsv"
expect_base table-missing 1 "is missing at the head" "$BASE"
mv "$R/svc/spec-fact.tsv" "$R/svc/spec-facts.tsv"
expect_base empty-sha 1 "needs a commit sha" ""
expect_base dash-sha 1 "needs a commit sha" "--x"
expect_base not-a-commit 1 "is not a commit" "0000000000000000000000000000000000000000"
[ $failed -eq 0 ] && echo "gst-ci: spec-facts self-test OK"
exit $failed
