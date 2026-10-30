#!/usr/bin/env bash
# One Kotlin file to a wasm32-wasip1 program with the native compiler (build/native/bin/minic-native, `./mini` builds it): scripts/wasm/build-one.sh prog.kt out-dir [clang -O flag]
# (relative paths, run from the repository root; needs build-runtime.sh output in build/wasm). Result: <out-dir>/<name>.wasm
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
src="$1"; out="$2"; opt="${3:--O1}"
n="$(basename "$src" .kt)"
w=build/wasm
mkdir -p "$out"
MINIC="${MINIC:-build/native/bin/minic-native}"
# shellcheck disable=SC2046
"$MINIC" --emit-ll "$out/$n.ll" --target=wasm32 "$src" --stdlib $(find stdlib -name '*.kt' | LC_ALL=C sort)
node scripts/wasm/yowasp.mjs clang --target=wasm32-wasip1 "$opt" -Wno-override-module -c "$out/$n.ll" -o "$out/$n.o"
node scripts/wasm/yowasp.mjs clang --target=wasm32-wasip1 "$out/$n.o" "$w/libminirt-wasm32.a" "$w/minirt-entry-wasm32.o" -o "$out/$n.wasm"
