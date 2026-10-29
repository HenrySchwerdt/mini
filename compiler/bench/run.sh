#!/usr/bin/env bash
# D1 benchmark driver (docs/benchmarks.md).
#
#   compiler/bench/run.sh [--reps N] [--configs "base opt"] [--only <substring>] [--out <dir>] [--cflags "-O2"]
#
# For every benchmark and every configuration it
#   1. turns the Kotlin program into MIR once (`minic emit-mir`),
#   2. runs the backend per configuration (`native-driver`; `base` = --no-rc-opt, `opt` = RC elision on),
#   3. compiles the C twice with clang against libminirt: a timing binary and a -DMK_STATS binary,
#   4. runs the timing binary --reps times (best wall time) and the stats binary once.
# Benchmarks: the micro programs in compiler/bench/micro/*.kt and the self-hosted backend (`minic-backend`,
# i.e. the native driver compiled by minic) turning MIR into C, on a small and on a large .mir.
# Results: <out>/results.tsv and a markdown table on stdout (also <out>/results.md).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
reps=3
configs="base opt"
only=""
out="$root/compiler/build/bench"
cflags="-O1"
while [ $# -gt 0 ]; do
    case "$1" in
    --reps) reps="$2"; shift 2 ;;
    --configs) configs="$2"; shift 2 ;;
    --only) only="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --cflags) cflags="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
    esac
done
CC="${CC:-clang}"
mkdir -p "$out"

echo "== building minic and native-driver" >&2
(cd "$root/compiler" && ./gradlew -q installDist) >&2 || exit 1
minic="$root/compiler/jvm-bridge/build/install/minic/bin/minic"
driver="$root/compiler/native-driver/build/install/native-driver/bin/native-driver"

# libminirt twice: plain and with statistics
for variant in plain stats; do
    lib="$out/libminirt_$variant.a"
    if [ ! -f "$lib" ] || [ -n "$(find "$root/runtime" -name '*.[ch]' -newer "$lib" -print -quit)" ]; then
        echo "== libminirt ($variant)" >&2
        objdir="$out/rt_$variant"
        rm -rf "$objdir"; mkdir -p "$objdir"
        extra=""
        [ "$variant" = stats ] && extra="-DMK_STATS"
        for f in "$root"/runtime/src/*.c; do
            "$CC" -std=c17 -O2 $extra -I"$root/runtime/include" -c "$f" -o "$objdir/$(basename "$f" .c).o"
        done
        rm -f "$lib"
        ar rcs "$lib" "$objdir"/*.o
    fi
done

now_ns() { date +%s%N; }

# build_bin <key> <config> <mir>: C from the backend, timing + statistics binaries (cached per key/config)
build_bin() {
    local key="$1" cfg="$2" mir="$3"
    local flag=""
    [ "$cfg" = base ] && flag="--no-rc-opt"
    local cfile="$out/$key.$cfg.c"
    [ -f "$out/$key.$cfg.stats.bin" ] && return 0
    "$driver" "$mir" -o "$cfile" $flag
    "$CC" -std=c17 $cflags -w -I"$root/runtime/include" "$cfile" "$out/libminirt_plain.a" -lm -o "$out/$key.$cfg.bin"
    "$CC" -std=c17 $cflags -w -DMK_STATS -I"$root/runtime/include" "$cfile" "$out/libminirt_stats.a" -lm -o "$out/$key.$cfg.stats.bin"
}

# run_one <label> <key> <config> <args...>: best of $reps timing runs, one statistics run, one TSV row
run_one() {
    local name="$1" key="$2" cfg="$3"
    shift 3
    local cfile="$out/$key.$cfg.c"
    local retains releases csize
    retains=$(grep -o 'mk_retain(' "$cfile" | wc -l)
    releases=$(grep -o 'mk_release(' "$cfile" | wc -l)
    csize=$(wc -c <"$cfile")
    local best="" i t0 t1 ms
    for ((i = 0; i < reps; i++)); do
        t0=$(now_ns)
        "$out/$key.$cfg.bin" "$@" >"$out/$name.$cfg.stdout" 2>/dev/null
        t1=$(now_ns)
        ms=$(( (t1 - t0) / 1000000 ))
        if [ -z "$best" ] || [ "$ms" -lt "$best" ]; then best="$ms"; fi
    done
    "$out/$key.$cfg.stats.bin" "$@" >"$out/$name.$cfg.stats.stdout" 2>"$out/$name.$cfg.stats.err"
    if ! cmp -s "$out/$name.$cfg.stdout" "$out/$name.$cfg.stats.stdout"; then echo "output differs between plain and stats build: $name $cfg" >&2; exit 1; fi
    local kv sum
    kv=$(grep '^mk-stats:' "$out/$name.$cfg.stats.err" | grep -o '[a-z_]*=[0-9]*' | tr '\n' ' ')
    # checksum of stdout and of the file written through `-o <file>` (the minic-backend benchmarks)
    local outfile="" prev="" arg
    for arg in "$@"; do [ "$prev" = -o ] && outfile="$arg"; prev="$arg"; done
    sum=$( { cat "$out/$name.$cfg.stdout"; [ -n "$outfile" ] && cat "$outfile"; true; } | cksum | cut -d' ' -f1)
    printf '%s\t%s\tms=%s static_retain=%s static_release=%s csize=%s outsum=%s %s\n' "$name" "$cfg" "$best" "$retains" "$releases" "$csize" "$sum" "$kv" >>"$out/results.tsv"
    echo "   $name $cfg: ${best} ms" >&2
}

: >"$out/results.tsv"

want() { [ -z "$only" ] || [[ "$1" == *"$only"* ]]; }

for src in "$here"/micro/*.kt; do
    name="$(basename "$src" .kt)"
    want "$name" || continue
    echo "== $name" >&2
    "$minic" emit-mir "$src" -o "$out/$name.mir" >&2
    for cfg in $configs; do build_bin "$name" "$cfg" "$out/$name.mir"; run_one "$name" "$name" "$cfg"; done
done

# the self-hosted backend: native-driver + backend compiled by minic; input = MIR of stdlib_collections / of the backend itself
if want selfhost; then
    echo "== selfhost" >&2
    backend_srcs=$(find "$root/compiler/backend/src/main/kotlin" -name '*.kt' | sort)
    driver_srcs=$(find "$root/compiler/native-driver/src/main/kotlin" -name '*.kt' | sort)
    # shellcheck disable=SC2086
    "$minic" emit-mir $driver_srcs $backend_srcs -o "$out/selfhost.mir" >&2
    "$minic" emit-mir "$root/compiler/testdata/stdlib_collections.kt" -o "$out/small_input.mir" >&2
    for cfg in $configs; do
        # the program under test is minic-backend; its arguments are an input .mir and the output .c
        build_bin selfhost "$cfg" "$out/selfhost.mir"
        run_one selfhost-small selfhost "$cfg" "$out/small_input.mir" -o "$out/selfhost-small.$cfg.out.c"
        run_one selfhost-large selfhost "$cfg" "$out/selfhost.mir" -o "$out/selfhost-large.$cfg.out.c"
    done
fi

# markdown
{
    echo "| benchmark | config | time (ms) | allocs | frees | peak live objs | peak bytes | retains | (atomic) | releases | (atomic) | no-op retain+release | static retain/release in C |"
    echo "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
    awk -F'\t' '{
        n = split($3, a, " ")
        delete v
        for (i = 1; i <= n; i++) { split(a[i], kv, "="); v[kv[1]] = kv[2] }
        printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s / %s |\n", $1, $2, v["ms"], v["allocs"], v["frees"], v["peak_live"], v["bytes_peak"], v["retains"], v["retains_atomic"], v["releases"], v["releases_atomic"], v["retains_noop"] + v["releases_noop"], v["static_retain"], v["static_release"]
    }' "$out/results.tsv"
} | tee "$out/results.md"

# outputs of base and opt must agree
awk -F'\t' '{ split($3, a, " "); for (i in a) if (a[i] ~ /^outsum=/) s = a[i]; if (($1) in seen && seen[$1] != s) { print "OUTPUT MISMATCH between configs: " $1 > "/dev/stderr"; bad = 1 } seen[$1] = s } END { exit bad }' "$out/results.tsv"
