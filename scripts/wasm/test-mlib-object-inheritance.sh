#!/usr/bin/env bash
# Proves separately compiled libraries keep inherited virtual/interface methods as dependency imports.
# Usage: scripts/wasm/test-mlib-object-inheritance.sh [wasm-dir] [minic-native]
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
w="${1:-build/wasm}"
minic="${2:-$root/build/native/bin/minic-native}"
stdlib="$root/build/native/lib/stdlib.mlib"
compiler_id="$(cat "$root/build/native/VERSION" 2>/dev/null || echo unknown)"
work="$w/mlib-object-inheritance"
rm -rf "$work"
mkdir -p "$work"

cat >"$work/base.kt" <<'EOF'
package shared

interface Readable {
    fun read(): Int
}

open class Base : Readable {
    override fun read(): Int = 7
}
EOF

cat >"$work/derived.kt" <<'EOF'
package shared

class Derived : Base()
EOF

cat >"$work/app.kt" <<'EOF'
package app

import shared.Base
import shared.Derived
import shared.Readable

fun main() {
    val derived = Derived()
    val base: Base = derived
    val readable: Readable = derived
    println(base.read())
    println(readable.read())
}
EOF

"$minic" --emit-mlib "$work/base.mlib" --name base --lib-version 0.0.0 --compiler-id "$compiler_id" \
    "$work/base.kt" --lib "$stdlib"
"$minic" --emit-mlib "$work/derived.mlib" --name derived --lib-version 0.0.0 --compiler-id "$compiler_id" \
    "$work/derived.kt" --lib "$stdlib" --lib "$work/base.mlib"

"$minic" --target=wasm32 --mlib-object "$stdlib" --emit-mlib-object-ll "$work/stdlib.ll"
"$minic" --target=wasm32 --mlib-object "$work/base.mlib" --emit-mlib-object-ll "$work/base.ll" --lib "$stdlib"
"$minic" --target=wasm32 --mlib-object "$work/derived.mlib" --emit-mlib-object-ll "$work/derived.ll" \
    --lib "$stdlib" --lib "$work/base.mlib"
"$minic" --target=wasm32 --wasm-separate-mlibs --emit-ll "$work/split.ll" "$work/app.kt" \
    --lib "$stdlib" --lib "$work/base.mlib" --lib "$work/derived.mlib"
"$minic" --target=wasm32 --emit-ll "$work/mono.ll" "$work/app.kt" \
    --lib "$stdlib" --lib "$work/base.mlib" --lib "$work/derived.mlib"

Y="node scripts/wasm/yowasp.mjs"
$Y --mount "$w" clang --target=wasm32-wasip1 -O1 -Wno-override-module -c "$work/stdlib.ll" -o "$work/stdlib.o"
$Y --mount "$w" clang --target=wasm32-wasip1 -O1 -Wno-override-module -c "$work/base.ll" -o "$work/base.o"
$Y --mount "$w" clang --target=wasm32-wasip1 -O1 -Wno-override-module -c "$work/derived.ll" -o "$work/derived.o"
$Y --mount "$w" clang --target=wasm32-wasip1 -O1 -Wno-override-module -c "$work/split.ll" -o "$work/split-app.o"
$Y --mount "$w" clang --target=wasm32-wasip1 -O1 -Wno-override-module -c "$work/mono.ll" -o "$work/mono-app.o"
$Y --mount "$w" clang --target=wasm32-wasip1 -Wl,--fatal-warnings \
    "$work/split-app.o" "$work/stdlib.o" "$work/base.o" "$work/derived.o" \
    "$w/libminirt-wasm32.a" "$w/minirt-entry-wasm32.o" -o "$work/split.wasm"
$Y --mount "$w" clang --target=wasm32-wasip1 -Wl,--fatal-warnings \
    "$work/mono-app.o" "$w/libminirt-wasm32.a" "$w/minirt-entry-wasm32.o" -o "$work/mono.wasm"

node --no-warnings scripts/wasm/run-wasi.mjs "$work/split.wasm" >"$work/split.out"
node --no-warnings scripts/wasm/run-wasi.mjs "$work/mono.wasm" >"$work/mono.out"
printf '7\n7\n' >"$work/expected.out"
cmp "$work/expected.out" "$work/split.out"
cmp "$work/expected.out" "$work/mono.out"
cmp "$work/mono.out" "$work/split.out"
echo "split inherited virtual/interface dispatch matches monolithic output"
