#!/usr/bin/env bash
# LLVM backend vs C backend (docs/plan-kotlin.md section 10g): the same MIR, the same RcOpt decisions, the same clang
# -O level and the same libminirt; only the code generator differs (CEmitter -> C -> clang, LlvmEmitter -> .ll -> clang).
#
#   compiler/bench/run-llvm.sh [--reps N] [--opts "-O1 -O2"] [--only <substring>] [--out <dir>]
#
# Benchmarks: compiler/bench/micro/*.kt and the self-hosted backend (`minic-backend`, the native driver compiled by minic)
# turning the MIR of the backend itself into C. Results: <out>/results.tsv and a markdown table on stdout.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
reps=3
opts="-O1 -O2"
only=""
out="$root/compiler/build/bench-llvm"
while [ $# -gt 0 ]; do
    case "$1" in
    --reps) reps="$2"; shift 2 ;;
    --opts) opts="$2"; shift 2 ;;
    --only) only="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
    esac
done
CC="${CC:-clang}"
mkdir -p "$out"

if [ -z "${BENCH_NO_BUILD:-}" ]; then
    echo "== building the native compiler and native-driver" >&2
    "$root/mini" --version >/dev/null
    (cd "$root/compiler" && "$root/build/native/bin/mini" build --release -p native-driver) >&2 || exit 1
fi
minic="$root/build/native/bin/minic-native"
driver="$root/compiler/build/release/native-driver"
stdlib_files=$(find "$root/stdlib" -name '*.kt' | LC_ALL=C sort)
# emit_mir <out.mir> <src.kt>...: the Kotlin program as MIR (the native compiler writes it with --emit-mir)
emit_mir() {
    local mir="$1"; shift
    # shellcheck disable=SC2086
    "$minic" --emit-mir "$mir" "$@" --stdlib $stdlib_files
}

lib="$out/libminirt_plain.a"
if [ ! -f "$lib" ] || [ -n "$(find "$root/runtime" -name '*.[ch]' -newer "$lib" -print -quit)" ]; then
    echo "== libminirt" >&2
    objdir="$out/rt"
    rm -rf "$objdir"; mkdir -p "$objdir"
    for f in "$root"/runtime/src/*.c; do
        "$CC" -std=c17 -O2 -I"$root/runtime/include" -c "$f" -o "$objdir/$(basename "$f" .c).o"
    done
    rm -f "$lib"
    ar rcs "$lib" "$objdir"/*.o
fi

now_ns() { date +%s%N; }

# time_bin <binary> <args...>: best wall time of $reps runs in ms (stdout to $out/last.stdout)
time_bin() {
    local bin="$1" best="" i t0 t1 ms
    shift
    for ((i = 0; i < reps; i++)); do
        t0=$(now_ns)
        "$bin" "$@" >"$out/last.stdout" 2>/dev/null
        t1=$(now_ns)
        ms=$(( (t1 - t0) / 1000000 ))
        if [ -z "$best" ] || [ "$ms" -lt "$best" ]; then best="$ms"; fi
    done
    echo "$best"
}

: >"$out/results.tsv"

# bench <name> <mir> <args...>: builds both variants at every -O level and times them
bench() {
    local name="$1" mir="$2" o c_ms ll_ms c_sum ll_sum t0 t1 t2 c_build ll_build
    shift 2
    "$driver" "$mir" -o "$out/$name.c" >&2
    "$driver" "$mir" -o "$out/$name.ll" --backend=llvm >&2
    for o in $opts; do
        t0=$(now_ns)
        "$CC" -std=c17 "$o" -w -I"$root/runtime/include" "$out/$name.c" "$lib" -lm -o "$out/$name.c$o.bin"
        t1=$(now_ns)
        "$CC" "$o" -Wno-override-module "$out/$name.ll" "$lib" -lm -o "$out/$name.ll$o.bin"
        t2=$(now_ns)
        c_build=$(( (t1 - t0) / 1000000 ))
        ll_build=$(( (t2 - t1) / 1000000 ))
        c_ms=$(time_bin "$out/$name.c$o.bin" "$@")
        c_sum=$( { cat "$out/last.stdout"; for a in "$@"; do [ -f "$a" ] && [ "$a" != "$mir" ] && cat "$a"; done; true; } | cksum | cut -d' ' -f1)
        ll_ms=$(time_bin "$out/$name.ll$o.bin" "$@")
        ll_sum=$( { cat "$out/last.stdout"; for a in "$@"; do [ -f "$a" ] && [ "$a" != "$mir" ] && cat "$a"; done; true; } | cksum | cut -d' ' -f1)
        local same=yes
        [ "$c_sum" = "$ll_sum" ] || same=NO
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$o" "$c_ms" "$ll_ms" "$same" "$(wc -c <"$out/$name.c")" "$(wc -c <"$out/$name.ll")" "$c_build" "$ll_build" >>"$out/results.tsv"
        echo "   $name $o: C $c_ms ms, LLVM $ll_ms ms (clang: C $c_build ms, .ll $ll_build ms)" >&2
    done
}

want() { [ -z "$only" ] || [[ "$1" == *"$only"* ]]; }

for src in "$here"/micro/*.kt; do
    name="$(basename "$src" .kt)"
    want "$name" || continue
    echo "== $name" >&2
    [ -f "$out/$name.mir" ] || emit_mir "$out/$name.mir" "$src" >&2
    bench "$name" "$out/$name.mir"
done

if want selfhost; then
    echo "== selfhost" >&2
    backend_srcs=$(find "$root/compiler/backend/src" -name '*.kt' | sort)
    driver_srcs=$(find "$root/compiler/native-driver/src" -name '*.kt' | sort)
    # shellcheck disable=SC2086
    [ -f "$out/selfhost.mir" ] || emit_mir "$out/selfhost.mir" $driver_srcs $backend_srcs >&2
    # the program under test is minic-backend (C backend and LLVM backend builds of it); its job: the large MIR to C
    bench selfhost "$out/selfhost.mir" "$out/selfhost.mir" -o "$out/selfhost.result.c"
fi

{
    echo "| benchmark | clang | C backend (ms) | LLVM backend (ms) | LLVM / C | same output | C size | .ll size | clang on .c (ms) | clang on .ll (ms) |"
    echo "|---|---|---:|---:|---:|---|---:|---:|---:|---:|"
    awk -F'\t' '{ printf "| %s | %s | %s | %s | %.2f | %s | %d | %d | %d | %d |\n", $1, $2, $3, $4, ($3 > 0 ? $4 / $3 : 0), $5, $6, $7, $8, $9 }' "$out/results.tsv"
} | tee "$out/results.md"
