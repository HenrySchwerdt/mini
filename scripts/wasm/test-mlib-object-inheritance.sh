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
    var weight: Long = 1L shl 33
    var count: Int = 5
    override fun read(): Int = 7
}

fun counterA(): () -> Int {
    var n = 5
    return { n += 1; n }
}

fun failNothing(): Nothing = throw IllegalStateException("nothing")

fun failInt(): Int = throw IllegalStateException("int")

fun forwardInt(): Int = failInt()
EOF

cat >"$work/derived.kt" <<'EOF'
package shared

// the fields of the three levels (Base here, Derived, the program's Leaf) are laid out by the shared layout function, with 4-byte
// pointers on wasm32: Base.count sits in the header's gap before the 8-aligned Long, Derived's fields follow Base's data end
open class Derived : Base() {
    var on: Boolean = true
    var small: Short = -3
    var tag: Byte = 9
    var label: String = "d"
}

fun counterB(): () -> Int {
    var n = 39
    return { n += 1; n }
}
EOF

cat >"$work/app.kt" <<'EOF'
package app

import shared.*

class Leaf : Derived() {
    var more: Int = 11
    var big: Long = -1L
    var flag: Boolean = false
    var next: Leaf? = null
}

fun main() {
    val leaf = Leaf()
    leaf.count += 1; leaf.tag = (leaf.tag + 1).toByte(); leaf.more = 12; leaf.flag = true; leaf.next = leaf; leaf.small = 4; leaf.on = false
    println("${leaf.weight} ${leaf.count} ${leaf.on} ${leaf.small} ${leaf.tag} ${leaf.label} ${leaf.more} ${leaf.big} ${leaf.flag} ${leaf.next === leaf}")
    val derived = Derived()
    val base: Base = derived
    val readable: Readable = derived
    println(base.read())
    println(readable.read())
    val a = counterA()
    println(a())
    println(a())
    val b = counterB()
    println(b())
    println(b())
    // the program's own mutable capture: its cell class (mk.Ref of Int) also exists in the stdlib object, which provides it
    var own = 100
    val bump = { own += 1 }
    bump()
    bump()
    println(own)
    try {
        failNothing()
    } catch (e: IllegalStateException) {
        println("caught nothing")
    }
    try {
        println(forwardInt())
    } catch (e: IllegalStateException) {
        println("caught forwarded int")
    }
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
printf '8589934592 6 false 4 10 d 12 -1 true true\n7\n7\n6\n7\n40\n41\n102\ncaught nothing\ncaught forwarded int\n' >"$work/expected.out"
cmp "$work/expected.out" "$work/split.out"
cmp "$work/expected.out" "$work/mono.out"
cmp "$work/mono.out" "$work/split.out"
# the layout with 4-byte pointers (12-byte header), computed by the same function for the libraries and the program: every level
# must place its fields behind the superclass's data end whichever compilation unit lays the class out
"$minic" --target=wasm32 -Xlayout-stats --emit-ll "$work/stats.ll" "$work/app.kt" \
    --lib "$stdlib" --lib "$work/base.mlib" --lib "$work/derived.mlib" 2>"$work/layout.txt" || true
grep -F 'shared.Base: ' "$work/layout.txt" | grep -F 'weight@16' | grep -F 'count@12' >/dev/null \
    || { echo "wasm32 layout of Base is wrong:"; grep -E 'Base|Derived|Leaf' "$work/layout.txt"; exit 1; }
grep -F 'shared.Derived: ' "$work/layout.txt" | grep -F 'weight@16' | grep -F 'count@12' | grep -F 'label@24' | grep -F 'small@28' \
    | grep -F 'on@30' | grep -F 'tag@31' >/dev/null \
    || { echo "wasm32 layout of Derived is wrong:"; grep -E 'Base|Derived|Leaf' "$work/layout.txt"; exit 1; }
grep -E '^(app\.)?Leaf: ' "$work/layout.txt" | grep -F 'more@' >/dev/null \
    || { echo "wasm32 layout of Leaf is missing:"; cat "$work/layout.txt"; exit 1; }
echo "split inherited dispatch and shared mutable-capture helpers match monolithic output"
