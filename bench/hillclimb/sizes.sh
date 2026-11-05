#!/usr/bin/env bash
# Stripped sizes of hello and the benchmarks for both Mini backends: bench/hillclimb/sizes.sh [bench...]
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
D="$ROOT/build/hc/sz"
mkdir -p "$D"
BENCHES=("$@")
[ ${#BENCHES[@]} -gt 0 ] || BENCHES=(startup/hello binarytrees nbody wordcount json graph)
for b in "${BENCHES[@]}"; do
    src="$ROOT/bench/$b/$(basename "$b").kt"
    line="$(basename "$b")"
    "$ROOT/build/native/bin/minic" build "$src" -o "$D/c" -O2 --backend c 2>/dev/null && strip -o "$D/c.s" "$D/c" && line="$line c=$(stat -c %s "$D/c.s")"
    "$ROOT/build/api/bin/minic" build "$src" -o "$D/a" -O2 --backend llvm-api 2>/dev/null && strip -o "$D/a.s" "$D/a" && line="$line api=$(stat -c %s "$D/a.s")"
    echo "$line"
done
