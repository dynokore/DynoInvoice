#!/usr/bin/env bash
# CS-57: fail on CJK text (Han, kana, Hangul, compatibility forms), invalid UTF-8 or a literal invisible/format character in any git-tracked text file.
# Prints file:line: U+XXXX for each hit and exits 1 if there is any. Binary files (a NUL byte in
# the first 8000 bytes, e.g. the archive/ zips) are skipped. This file holds code points as
# numbers only, so it passes its own check.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git ls-files -z | python3 -c '
import sys
R = [(0x85,0x85),(0xA0,0xA0),(0xAD,0xAD),(0x1680,0x1680),(0x180E,0x180E),(0x2000,0x200F),(0x2028,0x202F),
     (0x205F,0x2064),(0x2066,0x2069),(0x3000,0x3000),(0xFEFF,0xFEFF),
     (0x3001,0x303F),(0x3400,0x4DBF),(0x4E00,0x9FFF),(0xF900,0xFAFF),(0xFF01,0xFFEF),
     (0x1100,0x11FF),(0x3040,0x30FF),(0x3100,0x318F),(0x31A0,0x31FF),(0x3200,0x33FF),(0xA960,0xA97F),
     (0xAC00,0xD7FF),(0xFE30,0xFE4F),(0x20000,0x2FA1F)]
hits = 0
for name in sys.stdin.buffer.read().split(b"\0"):
    if not name: continue
    try: data = open(name, "rb").read()
    except OSError: continue
    if b"\0" in data[:8000]: continue
    try: text = data.decode("utf-8")
    except UnicodeDecodeError as e:
        print("%s:%d: invalid UTF-8" % (name.decode(), data[:e.start].count(b"\n") + 1)); hits += 1; continue
    for n, line in enumerate(text.split("\n"), 1):
        for ch in line:
            c = ord(ch)
            if c > 0x7F and any(a <= c <= b for a, b in R):
                print("%s:%d: U+%04X" % (name.decode(), n, c)); hits += 1
if hits:
    print("text-check: %d forbidden character(s); write them as escapes (CS-57)" % hits, file=sys.stderr)
    sys.exit(1)
'
