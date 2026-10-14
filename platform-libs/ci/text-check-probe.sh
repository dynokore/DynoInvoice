#!/usr/bin/env bash
# Probes for ci/text-check.sh (CS-57): each forbidden class must fail, accents and dashes must pass.
set -euo pipefail
chk="$(cd "$(dirname "$0")" && pwd)/text-check.sh"
d=$(mktemp -d); trap 'rm -rf "$d"' EXIT
git -C "$d" init -q
probe() { # name expected-rc printf-bytes
  printf "$3" > "$d/p.txt"; git -C "$d" add -f p.txt
  rc=0; (cd "$d" && "$chk") >/dev/null 2>&1 || rc=$?
  [ "$rc" = "$2" ] || { echo "probe $1: expected rc $2, got $rc"; exit 1; }
  echo "probe $1: ok"
}
probe han 1 'a\xe4\xb8\xadb\n'
probe hiragana 1 'a\xe3\x81\x82b\n'
probe katakana 1 'a\xe3\x82\xa2b\n'
probe halfwidth-katakana 1 'a\xef\xbd\xb1b\n'
probe hangul-syllable 1 'a\xea\xb0\x80b\n'
probe hangul-jamo 1 'a\xe1\x84\x80b\n'
probe cjk-compat 1 'a\xe3\x8e\x9db\n'
probe u0085 1 'a\xc2\x85b\n'
probe u3000 1 'a\xe3\x80\x80b\n'
probe invalid-utf8 1 'a\xffb\n'
probe accents-dashes 0 'caf\xc3\xa9 \xe2\x80\x93 \xe2\x80\x94 na\xc3\xafve \xc2\xa7\n'
