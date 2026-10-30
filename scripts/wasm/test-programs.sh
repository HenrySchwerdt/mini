#!/usr/bin/env bash
# End to end on wasm32-wasip1: every program of compiler/testdata (or the ones named) goes
#   minic-native --emit-ll --target=wasm32 -> clang (wasm) -> link with libminirt-wasm32.a
#   -> node:wasi, and stdout / exit code / stderr lines are compared with the .out / .err expectations
# exactly like LlvmEndToEndTest does for the native build.
#
#   scripts/wasm/test-programs.sh [name...]       env: MINIC (the native compiler, default build/native/bin/minic-native), WASM_DIR, JOBS
#   (run scripts/wasm/build-runtime.sh first; `./mini` builds the native compiler)
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
export MINIC="${MINIC:-$root/build/native/bin/minic-native}"
STDLIB="$(cd "$root" && find stdlib -name '*.kt' | LC_ALL=C sort | sed "s|^|$root/|" | tr '\n' ' ')"
export STDLIB
export WASM_DIR="${WASM_DIR:-build/wasm}"
export root
work="$WASM_DIR/prog"
rm -rf "$work"; mkdir -p "$work"
Y="node $root/scripts/wasm/yowasp.mjs"
export Y

one() {
    local name="$1" src="compiler/testdata/$1.kt" w="$WASM_DIR/prog"
    [ -f "$src" ] || src="examples/$name.kt"
    local log="$w/$name.log"
    (
        # shellcheck disable=SC2086
        "$MINIC" --emit-ll "$w/$name.ll" --target=wasm32 "$src" --stdlib $STDLIB >"$w/$name.compile" 2>&1 || { [ -f "compiler/testdata/$name.cerr" ] && exit 0; cat "$w/$name.compile"; exit 1; }
        $Y clang --target=wasm32-wasip1 -O1 -Wno-override-module -c "$w/$name.ll" -o "$w/$name.o" || exit 1
        $Y clang --target=wasm32-wasip1 "$w/$name.o" "$WASM_DIR/libminirt-wasm32.a" "$WASM_DIR/minirt-entry-wasm32.o" -o "$w/$name.wasm" || exit 1
        mkdir -p "$w/tmp"; node --no-warnings "$root/scripts/wasm/run-wasi.mjs" --dir "$w/tmp=/tmp" "$w/$name.wasm" >"$w/$name.stdout" 2>"$w/$name.stderr"
        code=$?
        local want="compiler/testdata/$name.out"
        [ -f "$want" ] || want="examples/$name.out"
        if [ -f "$want" ] && ! cmp -s "$want" "$w/$name.stdout"; then echo "stdout differs"; exit 1; fi
        if [ -f "compiler/testdata/$name.err" ]; then
            [ "$code" -ne 0 ] || { echo "should have failed"; exit 1; }
            while IFS= read -r line; do
                [ -z "$line" ] || grep -qF -- "$line" "$w/$name.stderr" || { echo "stderr lacks: $line"; exit 1; }
            done <"compiler/testdata/$name.err"
        else
            [ "$code" -eq 0 ] || { echo "exit $code"; cat "$w/$name.stderr"; exit 1; }
        fi
    ) >"$log" 2>&1
    local rc=$?
    if [ $rc -eq 0 ]; then echo "ok   $name"; else echo "FAIL $name (see $log)"; fi
    return $rc
}
export -f one

if [ $# -gt 0 ]; then names=("$@"); else
    names=()
    for f in compiler/testdata/*.kt; do
        n="$(basename "$f" .kt)"
        # programs that deliberately do not compile (.cerr) never reach the backend
        # C interop programs bind LP64 libc signatures (strlen as Long) and need their own C files: not part of the browser toolchain
        case "$n" in cinterop_*) continue ;; esac
        [ -f "compiler/testdata/$n.cerr" ] || names+=("$n")
    done
fi
printf '%s\n' "${names[@]}" | xargs -P "${JOBS:-4}" -I{} bash -c 'one {}' | sort | tee "$work/summary.txt"
fails=$(grep -c '^FAIL' "$work/summary.txt")
echo "$(grep -c '^ok' "$work/summary.txt") ok, $fails failed"
[ "$fails" -eq 0 ]
