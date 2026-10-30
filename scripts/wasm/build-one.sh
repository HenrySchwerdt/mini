#!/usr/bin/env bash
# One Kotlin file to a wasm32-wasip1 program with the JVM compiler: scripts/wasm/build-one.sh prog.kt out-dir [clang -O flag]
# (relative paths, run from the repository root; needs build-runtime.sh output in build/wasm). Result: <out-dir>/<name>.wasm
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
src="$1"; out="$2"; opt="${3:--O1}"
n="$(basename "$src" .kt)"
w=build/wasm
mkdir -p "$out"
compiler/jvm-bridge/build/install/minic/bin/minic emit-mir "$src" -o "$out/$n.mir" 2>&1 | grep -v "Picked up" || true
compiler/native-driver/build/install/native-driver/bin/native-driver "$out/$n.mir" --backend=llvm --target=wasm32 -o "$out/$n.ll" 2>&1 | grep -v "Picked up" || true
node scripts/wasm/yowasp.mjs clang --target=wasm32-wasip1 "$opt" -Wno-override-module -c "$out/$n.ll" -o "$out/$n.o"
node scripts/wasm/yowasp.mjs clang --target=wasm32-wasip1 "$out/$n.o" "$w/libminirt-wasm32.a" "$w/minirt-entry-wasm32.o" -o "$out/$n.wasm"
