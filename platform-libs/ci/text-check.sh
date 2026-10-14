#!/bin/sh
# ci/text-check.sh (INV-T-4708): refuse CJK text and literal invisible or format characters in any
# git-tracked text file. Source code must write such characters as \uXXXX escapes of the same code
# point (owner English-only rule 2026-10-12; coordinator ruling INV-Q-4706 (A)).
#
# Prints file:line and U+XXXX for every hit and exits 1 if there is one. Needs only git, od and sh.
# Binary files are skipped the way git grep skips them (-I). The patterns are UTF-8 byte sequences
# written as octal escapes, so this file itself holds no literal character it refuses.
#
# Refused code points:
#   CJK:       U+3001-U+303F, U+3400-U+4DBF, U+4E00-U+9FFF, U+F900-U+FAFF, U+FF01-U+FFEF
#   invisible: U+00A0, U+00AD, U+1680, U+180E, U+2000-U+200F, U+2028-U+202F, U+205F-U+2064,
#              U+2066-U+2069, U+3000, U+FEFF
set -eu

cd "$(git rev-parse --show-toplevel)"
LC_ALL=C
export LC_ALL

pat=''
pat=$pat'\302[\240\255]'
pat=$pat'|\341\232\200|\341\240\216'
pat=$pat'|\342\200[\200-\217\250-\257]'
pat=$pat'|\342\201[\237-\244\246-\251]'
pat=$pat'|\343\200[\200-\277]'
pat=$pat'|\343[\220-\277][\200-\277]'
pat=$pat'|\344[\200-\265][\200-\277]|\344\266[\200-\277]'
pat=$pat'|\344[\270-\277][\200-\277]|[\345-\351][\200-\277][\200-\277]'
pat=$pat'|\357[\244-\253][\200-\277]'
pat=$pat'|\357\273\277'
pat=$pat'|\357\274[\201-\277]|\357[\275\276][\200-\277]|\357\277[\200-\257]'
pat="$(printf "$pat")"

found=0
rc=0
hits="$(git grep --no-color -I -n -o -E -e "$pat")" || rc=$?
if [ "$rc" -gt 1 ]; then
  echo "text-check: git grep failed (exit $rc)" >&2
  exit 2
fi
if [ -n "$hits" ]; then
  # Each line is file:line:matched-bytes; decode the bytes to a code point.
  printf '%s\n' "$hits" | while IFS= read -r row; do
    loc="${row%:*}"
    bytes="${row##*:}"
    set -- $(printf '%s' "$bytes" | od -An -v -tu1)
    case $# in
      2) cp=$((($1 & 31) * 64 + ($2 & 63))) ;;
      3) cp=$((($1 & 15) * 4096 + ($2 & 63) * 64 + ($3 & 63))) ;;
      *) cp=0 ;;
    esac
    printf '%s: U+%04X\n' "$loc" "$cp"
  done
  found=1
fi

if [ "$found" -ne 0 ]; then
  echo "text-check: CJK text or invisible characters found; write each as a \\uXXXX escape of the same code point" >&2
  exit 1
fi
echo "text-check: ok"
