#!/usr/bin/env bash
# Deterministic instruction counts (valgrind --tool=callgrind) of scaled-down versions of the benchmarks: the noise-free proxy
# for the "instructions per operation" part of the speed metric (the box that scores the hillclimb is shared and noisy).
#   bench/hillclimb/icount.sh <c|api> [--top N] [bench...]        benches: graph wordcount strbuild json binarytrees
# Prints "<bench> <instructions>"; with --top N also the N most expensive functions of each.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KIND="${1:-c}"; shift
TOP=0
if [ "${1:-}" = "--top" ]; then TOP="$2"; shift 2; fi
if [ "$KIND" = api ]; then MINIC="$ROOT/build/api/bin/minic"; BE=llvm-api; else MINIC="$ROOT/build/native/bin/minic"; BE=c; fi
BENCHES=("$@")
[ ${#BENCHES[@]} -gt 0 ] || BENCHES=(graph wordcount strbuild json binarytrees)
D="$ROOT/build/hc/ic"
mkdir -p "$D"
scale() { # bench -> sed script that shrinks the workload
    case "$1" in
        graph) echo 's/NODES = 1000000/NODES = 100000/; s/SOURCES = 6/SOURCES = 2/' ;;
        wordcount) echo 's/WORDS = 20000000/WORDS = 600000/' ;;
        strbuild) echo 's/APPENDS = 12000000/APPENDS = 400000/; s/CONCATS = 6000000/CONCATS = 200000/' ;;
        json) echo 's/const val TOP = 60000/const val TOP = 4000/; s/const val REPS = 5/const val REPS = 2/' ;;
        binarytrees) echo 's/MAX_DEPTH = 18/MAX_DEPTH = 14/; s/maxDepth = 18/maxDepth = 14/' ;;
        *) echo '' ;;
    esac
}
for b in "${BENCHES[@]}"; do
    sed "$(scale "$b")" "$ROOT/bench/$b/$b.kt" > "$D/$b.kt"
    if ! "$MINIC" build "$D/$b.kt" -o "$D/$b" -O2 --backend "$BE" 2>"$D/$b.err"; then echo "$b BUILD-FAILED"; continue; fi
    valgrind --tool=callgrind --callgrind-out-file="$D/$b.cg" "$D/$b" >/dev/null 2>"$D/$b.vg"
    n="$(grep -o 'refs: *[0-9,]*' "$D/$b.vg" | tr -dc '0-9')"
    echo "$b $n"
    if [ "$TOP" -gt 0 ]; then callgrind_annotate "$D/$b.cg" 2>/dev/null | sed -n "24,$((24 + TOP - 1))p" | cut -c1-140; fi
done
