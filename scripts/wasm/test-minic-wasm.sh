#!/usr/bin/env bash
# The whole browser pipeline under node: minic.wasm (WASI, preopened /work with the stdlib and the sources)
#   --check on Strict programs, then --emit-ll --target=wasm32 for hello.kt and some testdata programs
#   -> clang (wasm) -> link with libminirt-wasm32.a -> run under node:wasi, compared with the .out files.
# Optional: MINIC_NATIVE=<native minic-native> also compares the written IR byte for byte.
#   scripts/wasm/test-minic-wasm.sh [wasm-dir]   (default build/wasm; relative, run from the repository root)
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
w="${1:-build/wasm}"
t="$w/mt"
Y="node scripts/wasm/yowasp.mjs"
run_minic() { node --no-warnings scripts/wasm/run-wasi.mjs --dir "$t=/work" "$w/minic.wasm" "$@"; }
ms() { date +%s%3N; }

rm -rf "$t"; mkdir -p "$t/tmp"
cp -r stdlib "$t/stdlib"
cp examples/hello.kt "$t/"
cp compiler/testdata/*.kt compiler/testdata/*.out "$t/" 2>/dev/null || true
cp -r compiler/testdata/memory "$t/memory"
STD=$(cd "$t" && find stdlib -name '*.kt' | LC_ALL=C sort | sed 's|^|/work/|' | tr '\n' ' ')

echo "== check (memory checker in wasm)"
s=$(ms)
if run_minic --check --brief /work/memory/uaf_simple.kt --stdlib $STD >"$t/check-bad.txt" 2>"$t/check-bad.err"; then
    echo "FAIL: --check accepted memory/uaf_simple.kt" >&2; exit 1
fi
grep -q M0101 "$t/check-bad.txt" || { echo "FAIL: no M0101 in the diagnostics"; cat "$t/check-bad.txt" "$t/check-bad.err"; exit 1; }
run_minic --check --brief /work/memory/ok_heap_basic.kt --stdlib $STD
echo "check: 2 programs, $(( $(ms) - s )) ms"

compile_run() { # name
    local n="$1" a b c
    a=$(ms)
    run_minic --emit-ll "/work/$n.ll" --target=wasm32 "/work/$n.kt" --stdlib $STD
    b=$(ms)
    $Y clang --target=wasm32-wasip1 -O1 -Wno-override-module -c "$t/$n.ll" -o "$t/$n.o"
    $Y clang --target=wasm32-wasip1 "$t/$n.o" "$w/libminirt-wasm32.a" "$w/minirt-entry-wasm32.o" -o "$t/$n.wasm"
    c=$(ms)
    node --no-warnings scripts/wasm/run-wasi.mjs --dir "$t/tmp=/tmp" "$t/$n.wasm" >"$t/$n.stdout"
    cmp "$t/$n.stdout" "$t/$n.out" || { echo "FAIL: output of $n differs"; exit 1; }
    # With a natively built minic-native (MINIC_NATIVE) the IR must be identical: the wasm build is the same compiler.
    # (The JVM compiler orders some declarations differently because its HashMap iterates in another order; same program.)
    if [ -n "${MINIC_NATIVE:-}" ]; then
        "$MINIC_NATIVE" --emit-ll "$t/$n.native.ll" --target=wasm32 "$t/$n.kt" --stdlib $(cd "$t" && find stdlib -name '*.kt' | LC_ALL=C sort | sed "s|^|$t/|")
        cmp "$t/$n.ll" "$t/$n.native.ll" || { echo "FAIL: IR of $n differs from the native compiler's"; exit 1; }
    fi
    echo "ok $n: minic.wasm emit-ll $((b - a)) ms, clang+link $((c - b)) ms, ll $(stat -c %s "$t/$n.ll") bytes, wasm $(stat -c %s "$t/$n.wasm") bytes"
}
cp examples/hello.kt "$t/hello.kt"
printf 'Hello, Mini!\n1 * 1 = 1\n2 * 2 = 4\n3 * 3 = 9\n' >"$t/hello.out"
echo "== compile and run"
for n in hello ${MINIC_WASM_PROGRAMS:-classes_basic closures strings exceptions}; do compile_run "$n"; done
echo "minic.wasm pipeline OK"
