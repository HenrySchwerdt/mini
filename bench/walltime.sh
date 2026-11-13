#!/usr/bin/env bash
# Wall-time comparison: mini (C backend), mini (LLVM API backend) and Rust, median of N pinned runs.
#
#   bench/walltime.sh [-n RUNS] [-c CPU] [-o DIR] [prog ...]
#
#   -n RUNS   timed runs per binary after one warm-up run (default 7)
#   -c CPU    the core every run is pinned to with taskset (default: the last core; "none" = no pinning)
#   -o DIR    work directory for the binaries (default: a temporary one, removed at the end)
#   prog      benchmarks of bench/ (default: all that have a .kt and a .rs)
#
# The LLVM column: `compiler/bootstrap.sh --backend=llvm-api <dir>` (LLVM 18 and `llvm-config` installed; about 8 minutes) builds an API compiler, the file
# <dir>/stage3 is the MINIC_API to pass. It must be built from the sources that are measured (it carries its own copy of the optimizer and the stdlib lowering).
# Environment: MINIC (default <repo>/minic, the C-backend driver), MINIC_API (a minic-native built with the LLVM C API, e.g. the
# stage3 of `compiler/bootstrap.sh --backend=llvm-api`; the LLVM column is skipped without it), MINI_LIB (default build/native/lib/stdlib.mlib),
# RUNTIME_LIB (default build/native/lib/libminirt.a), CC, RUSTC. The binaries are run one after the other, interleaved per program (C, LLVM, Rust,
# C, LLVM, Rust ...) so that slow drifts of a shared machine hit all of them alike. Output: a Markdown table with the medians in seconds and
# the ratio to Rust, plus the minimum of the runs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNS=7
CPU=""
OUT=""
while getopts "n:c:o:" o; do
    case "$o" in n) RUNS="$OPTARG" ;; c) CPU="$OPTARG" ;; o) OUT="$OPTARG" ;; *) exit 2 ;; esac
done
shift $((OPTIND - 1))
PROGS=("$@")
if [ ${#PROGS[@]} -eq 0 ]; then
    for d in "$ROOT"/bench/*/; do
        n="$(basename "$d")"
        [ -f "$d/$n.kt" ] && [ -f "$d/$n.rs" ] && PROGS+=("$n")
    done
fi
[ -n "$CPU" ] || CPU="$(( $(nproc) - 1 ))"
PIN=()
if [ "$CPU" != none ] && command -v taskset >/dev/null 2>&1; then PIN=(taskset -c "$CPU"); fi
MINIC="${MINIC:-$ROOT/minic}"
LIB="${MINI_LIB:-$ROOT/build/native/lib/stdlib.mlib}"
RT="${RUNTIME_LIB:-$ROOT/build/native/lib/libminirt.a}"
INC="$ROOT/build/native/include"
CC="${CC:-clang}"
RUSTC="${RUSTC:-rustc}"
if [ -z "$OUT" ]; then OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT; else mkdir -p "$OUT"; fi

build() { # build all binaries of one program into $OUT
    local p="$1" kt="$ROOT/bench/$1/$1.kt" rs="$ROOT/bench/$1/$1.rs"
    "$MINIC" build -O2 --backend c "$kt" -o "$OUT/$p.mc" >/dev/null 2>&1 || echo "build failed: $p (C)" >&2
    if [ -n "${MINIC_API:-}" ]; then
        "$MINIC_API" --backend=llvm-api --opt=O2 -o "$OUT/$p.llvm.o" "$kt" --lib "$LIB" --compiler-id "$(cat "$ROOT/build/native/VERSION")" >/dev/null 2>&1 &&
            "$CC" "$OUT/$p.llvm.o" "$RT" -lm -Wl,--gc-sections -o "$OUT/$p.llvm" >/dev/null 2>&1 || echo "build failed: $p (LLVM)" >&2
    fi
    "$RUSTC" -C opt-level=3 -C codegen-units=1 -C strip=symbols "$rs" -o "$OUT/$p.rs" >/dev/null 2>&1 || echo "build failed: $p (Rust)" >&2
}

for p in "${PROGS[@]}"; do build "$p"; done

PIN_STR="${PIN[*]:-}"
python3 - "$OUT" "$RUNS" "$PIN_STR" "${PROGS[@]}" <<'EOF'
import os, subprocess, sys, time, statistics
out, runs, pin = sys.argv[1], int(sys.argv[2]), sys.argv[3].split()
progs = sys.argv[4:]
def one(exe):
    t = time.perf_counter()
    subprocess.run(pin + [exe], stdout=subprocess.DEVNULL, check=True)
    return time.perf_counter() - t
print("| benchmark | mini C (s) | mini LLVM (s) | Rust (s) | C / Rust | LLVM / Rust | min C | min LLVM | min Rust |")
print("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
for p in progs:
    exes = {"c": os.path.join(out, p + ".mc"), "llvm": os.path.join(out, p + ".llvm"), "rs": os.path.join(out, p + ".rs")}
    exes = {k: v for k, v in exes.items() if os.path.exists(v)}
    for e in exes.values(): one(e)  # warm-up (page cache, CPU frequency)
    t = {k: [] for k in exes}
    for _ in range(runs):
        for k, e in exes.items(): t[k].append(one(e))
    med = {k: statistics.median(v) for k, v in t.items()}
    mn = {k: min(v) for k, v in t.items()}
    f = lambda d, k: ("%.3f" % d[k]) if k in d else "-"
    r = lambda k: ("%.2f" % (med[k] / med["rs"])) if k in med and "rs" in med else "-"
    print("| %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (p, f(med, "c"), f(med, "llvm"), f(med, "rs"), r("c"), r("llvm"), f(mn, "c"), f(mn, "llvm"), f(mn, "rs")))
EOF
