#!/usr/bin/env bash
# Runs the Strict-mode usability corpus (compiler/testdata/strict-corpus) through the memory checker.
#
#   scripts/strict-corpus.sh            check every program; exit 1 when any expectation is not met
#   scripts/strict-corpus.sh --run      also build and run the accepted programs and compare with .out
#   scripts/strict-corpus.sh s01 s02    show the full diagnostics of the programs with these name prefixes
#   scripts/strict-corpus.sh --list     print one line per program (name, verdict)
#
# Each program starts with `// ACCEPT: why` (must compile, optional name.out is its stdout) or
# `// REJECT Mxxxx: why` (the checker must report exactly that code and nothing else).
# Counters: ok = as expected, FP = false positive (accepted program rejected), FN = false negative (bad program accepted),
# WRONG = rejected with another code.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="${MINI_NATIVE_DIR:-$ROOT/build/native}"
MINIC="${MINIC_NATIVE:-$NATIVE/bin/minic-native}"
DIR="$ROOT/compiler/testdata/strict-corpus"
RUN=0; LIST=0; SHOW=()
for a in "$@"; do case "$a" in --run) RUN=1;; --list) LIST=1;; --*) ;; *) SHOW+=("$a");; esac; done
if [ -f "$NATIVE/lib/stdlib.mlib" ]; then
    LIB=(--lib "$NATIVE/lib/stdlib.mlib" --compiler-id "$(cat "$NATIVE/VERSION" 2>/dev/null || echo unknown)")
else
    LIB=(--stdlib $(find "$ROOT/stdlib" -name '*.kt' | LC_ALL=C sort))
fi
if [ ${#SHOW[@]} -gt 0 ]; then   # scripts/strict-corpus.sh s01 s02: full human-readable diagnostics of those programs
    for n in "${SHOW[@]}"; do for f in "$DIR/$n"*.kt; do echo "== $f"; "$MINIC" --check --color=never "$f" "${LIB[@]}" 2>&1 | head -60; done; done
    exit 0
fi
W="$(mktemp -d "${TMPDIR:-/tmp}/strict-corpus.XXXXXX")"
trap 'rm -rf "$W"' EXIT
ok=0; fp=0; fn=0; wrong=0; runfail=0
for f in "$DIR"/*.kt; do
    n="$(basename "$f" .kt)"
    h="$(head -1 "$f")"
    got="$("$MINIC" --check --brief "$f" "${LIB[@]}" 2>&1 | grep -o 'M0[0-9]*' | LC_ALL=C sort -u | tr '\n' ' ' | sed 's/ $//')"
    verdict=ok
    case "$h" in
        "// ACCEPT"*) [ -z "$got" ] || { verdict="FP($got)"; fp=$((fp+1)); } ;;
        "// REJECT"*)
            want="$(echo "$h" | grep -o 'M0[0-9]*' | head -1)"
            if [ -z "$got" ]; then verdict="FN(want $want)"; fn=$((fn+1))
            elif [ "$got" != "$want" ]; then verdict="WRONG(want $want got $got)"; wrong=$((wrong+1)); fi ;;
    esac
    if [ "$verdict" = ok ]; then
        ok=$((ok+1))
        if [ $RUN = 1 ] && [ -f "${f%.kt}.out" ]; then
            if "$NATIVE/bin/minic" run "$f" > "$W/out" 2> "$W/err" && cmp -s "$W/out" "${f%.kt}.out"; then :
            else verdict="RUNFAIL"; runfail=$((runfail+1)); ok=$((ok-1)); fi
        fi
    fi
    if [ $LIST = 1 ] || [ "$verdict" != ok ]; then echo "$n: $verdict"; fi
done
echo "strict-corpus: ok=$ok false-positives=$fp false-negatives=$fn wrong-code=$wrong run-failures=$runfail"
[ $fp -eq 0 ] && [ $fn -eq 0 ] && [ $wrong -eq 0 ] && [ $runfail -eq 0 ]
