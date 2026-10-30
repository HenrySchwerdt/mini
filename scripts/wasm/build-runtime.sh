#!/usr/bin/env bash
# libminirt for wasm32-wasip1 with the prebuilt YoWASP clang: build/wasm/libminirt-wasm32.a (+ entry object).
#   scripts/wasm/build-runtime.sh [out-dir]        (default build/wasm; run from the repository root)
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
out="${1:-build/wasm}"
Y="node scripts/wasm/yowasp.mjs"
[ -d scripts/wasm/node_modules ] || npm ci --prefix scripts/wasm

mkdir -p "$out/rt"
objs=()
for f in runtime/src/*.c; do
    b="$(basename "$f" .c)"
    # entry.c is the C `main`; the generated program links it separately (libminirt stays main-less like natively).
    $Y --mount runtime clang --target=wasm32-wasip1 -std=c17 -O2 -Wall -Wextra -Werror \
        -Iruntime/include -Iruntime/src -c "$f" -o "$out/rt/$b.o"
    [ "$b" = entry ] || objs+=("$out/rt/$b.o")
done
rm -f "$out/libminirt-wasm32.a"
$Y --mount "$out/rt" ar rcs "$out/libminirt-wasm32.a" "${objs[@]}"
cp "$out/rt/entry.o" "$out/minirt-entry-wasm32.o"
ls -l "$out/libminirt-wasm32.a" "$out/minirt-entry-wasm32.o"
