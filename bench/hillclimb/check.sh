#!/usr/bin/env bash
# Quick correctness check of benchmarks with the Mini toolchain: bench/hillclimb/check.sh <c|api> [bench...]
# (builds each with -O2 and compares stdout with expected.txt; no timing)
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KIND="${1:-c}"; shift
if [ "$KIND" = api ]; then MINIC="$ROOT/build/api/bin/minic"; BE=llvm-api; else MINIC="$ROOT/build/native/bin/minic"; BE=c; fi
BENCHES=("$@")
[ ${#BENCHES[@]} -gt 0 ] || BENCHES=(binarytrees nbody fannkuch spectralnorm mandelbrot wordcount strbuild json graph)
mkdir -p "$ROOT/build/hc"
rc=0
for b in "${BENCHES[@]}"; do
    if "$MINIC" build "$ROOT/bench/$b/$b.kt" -o "$ROOT/build/hc/chk_$b" -O2 --backend "$BE" 2>"$ROOT/build/hc/chk_$b.err" &&
       "$ROOT/build/hc/chk_$b" | cmp -s - "$ROOT/bench/$b/expected.txt"; then echo "ok   $b ($KIND)"; else echo "FAIL $b ($KIND)"; rc=1; fi
done
exit $rc
