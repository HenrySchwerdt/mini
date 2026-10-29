#!/usr/bin/env bash
# Regenerates the compiler-output listings shown on the "How it works" pages from the real compiler.
# Everything lands in website/internals/generated/ (committed, so the site builds without a compiler):
#   counter.mir.txt          MIR of samples/internals/counter.kt (runtime exception classes dropped)
#   counter.c.txt            its generated C (the fixed preamble of helper macros is dropped)
#   counter.noopt.main.c.txt the same main function built with --no-rc-opt
#   throws.*.c.txt           selected functions of samples/internals/throws.kt (error-return ABI)
#   closure.*.c.txt, generics.box.c.txt  selected parts of samples/internals/closure.kt (closure, inline expansion, erased generics)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$root/website/internals/generated"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$out"
cd "$root"

quiet() { "$@" 2> >(grep -v JAVA_TOOL_OPTIONS >&2); }

# extract FILE LABEL: the section the emitter starts with the comment "/* LABEL */", up to the next blank line
extract() { awk -v label="/* $2 */" '$0 == label { on = 1 } on && /^$/ { exit } on { print }' "$1"; }

src=website/samples/internals/counter.kt
quiet ./minic emit-mir "$src" -o "$tmp/counter.mir"
grep -Ev '^  \(class "(java|kotlin)\.' "$tmp/counter.mir" > "$out/counter.mir.txt"
quiet ./minic build "$src" -o "$tmp/counter" --emit-c "$tmp/counter.c"
sed -n '/^\/\* prototypes \*\//,$p' "$tmp/counter.c" > "$out/counter.c.txt"
quiet ./minic build "$src" -o "$tmp/counter0" --no-rc-opt --emit-c "$tmp/counter0.c"
extract "$tmp/counter0.c" main > "$out/counter.noopt.main.c.txt"

quiet ./minic build website/samples/internals/throws.kt -o "$tmp/throws" --emit-c "$tmp/throws.c"
extract "$tmp/throws.c" half > "$out/throws.half.c.txt"
extract "$tmp/throws.c" main > "$out/throws.main.c.txt"

quiet ./minic build website/samples/internals/closure.kt -o "$tmp/closure" --emit-c "$tmp/closure.c"
extract "$tmp/closure.c" 'class adder.<lambda>$0' > "$out/closure.class.c.txt"
extract "$tmp/closure.c" 'adder' > "$out/closure.adder.c.txt"
extract "$tmp/closure.c" 'adder.<lambda>$0.invoke' > "$out/closure.invoke.c.txt"
extract "$tmp/closure.c" 'class Box' > "$out/generics.box.c.txt"
extract "$tmp/closure.c" 'Box.get' >> "$out/generics.box.c.txt"
awk '/MkInt v_n_4 = 0;/ { on = 1 } on { print } /^    brk_0:;/ { exit }' "$tmp/closure.c" > "$out/closure.inline.c.txt"
echo "generated listings in website/internals/generated"
