#!/usr/bin/env bash
# End-to-end tests of the native compiler over compiler/testdata (no JVM). One test per program and suite, run in parallel.
#
#   scripts/test-e2e.sh [-j N] [--suite NAME]... [--list] [filter]
#
# Suites (default: c leak memcheck memrun strict multifile mlib):
#   c          build every compiler/testdata/*.kt through the C backend (cc -std=c17 -Wall -Werror), run it, compare
#                <name>.out (stdout), <name>.err (substrings of stderr; the program must then exit non-zero),
#                <name>.cerr (the build must fail with exit 1 and the output must contain each line)
#   strict-leak, strict-llvm-leak   the runnable programs of the strict corpus under AddressSanitizer + LeakSanitizer (C and LLVM backend)
#   leak       the same programs (and the Strict programs of testdata/memory with a .out) built with AddressSanitizer +
#                LeakSanitizer + UBSan: same output, exit 0, no report. Skipped without a C compiler that links the sanitizers.
#                There is no opt-out: a program that leaks is a bug (reference cycles are collected, see docs/memory-model.md).
#   stress     compiler/testdata/stress: allocation-heavy programs (millions of small objects, maps of lists, string building, cyclic
#                structures built and dropped over and over). stress-leak / stress-llvm-leak: LeakSanitizer clean, output equals .out;
#                stress-rss: the peak RSS (scripts/peak-rss.py) with 4x the rounds stays within 25 percent (+8 MiB) of the peak with
#                1x, and under the limit in `<name>.rss` (KiB) when there is one: memory must not grow with the number of rounds
#   llvm       like `c`, through the LLVM backend (MIR -> textual LLVM IR -> clang)
#   llvm-leak  the sanitizer run on the LLVM build
#   llvm-variants  --stats, --no-rc-opt, --no-strict and --no-conv through bin/native-driver (needs NATIVE_DRIVER)
#   llvm-api   the in-process LLVM backend (needs a minic-native built with the LLVM C API: MINIC_API, see compiler/bootstrap.sh
#                --backend=llvm-api, and llvm-config)
#   memcheck   `minic-native --check --brief` over testdata/memory and memory-extra equals the .diag files
#   memrun     the accepted programs of testdata/memory (.out) built and run (programs using constructs the C backend cannot
#                lower yet, or threads, are skipped and counted)
#   strict     compiler/testdata/strict-corpus: everyday Strict programs; `// ACCEPT` ones must pass the checker (and, with a .out,
#                build and run to it), `// REJECT Mxxxx` ones must be rejected with exactly that code (scripts/strict-corpus.sh)
#   multifile  several input files, reachability pruning, all user files are memory-checked
#   mlib       precompiled libraries (docs/mlib.md): a chain of two libraries, error messages for damaged and mismatching ones
#
# Environment: E2E_STDLIB=source compiles the stdlib sources with every program (default: lib/stdlib.mlib), MINI_ROOT (default <repo>/build/native: bin/minic-native, lib/libminirt.a, include/), MINIC_NATIVE, CC,
#              NATIVE_DRIVER, MINIC_API, E2E_KEEP=1 keeps the work directory.
# Exit status 0 when every test passed (skips are reported, not failures).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="${MINI_ROOT:-$ROOT/build/native}"
MINIC="${MINIC_NATIVE:-$NATIVE/bin/minic-native}"
RTLIB="$NATIVE/lib/libminirt.a"
INCLUDE="$ROOT/runtime/include"
TESTDATA="$ROOT/compiler/testdata"
CC="${CC:-}"
if [ -z "$CC" ]; then if command -v clang >/dev/null 2>&1; then CC=clang; else CC=cc; fi; fi
JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 2)"
TIMEOUT_CMD=""
if command -v timeout >/dev/null 2>&1; then TIMEOUT_CMD="$(command -v timeout)"
elif command -v gtimeout >/dev/null 2>&1; then TIMEOUT_CMD="$(command -v gtimeout)"
fi
SUITES=()
FILTER=""
LIST=0
while [ $# -gt 0 ]; do
    case "$1" in
        -j) JOBS="$2"; shift 2 ;;
        --suite) SUITES+=("$2"); shift 2 ;;
        --list) LIST=1; shift ;;
        -h|--help) sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) echo "test-e2e: unknown option $1" >&2; exit 2 ;;
        *) FILTER="$1"; shift ;;
    esac
done
[ ${#SUITES[@]} -gt 0 ] || SUITES=(c leak memcheck memrun strict strict-leak strict-llvm-leak multifile mlib stress-leak stress-rss)

[ -x "$MINIC" ] || { echo "test-e2e: no native compiler at $MINIC (run ./mini once, or set MINIC_NATIVE)" >&2; exit 2; }
[ -f "$RTLIB" ] || { echo "test-e2e: no runtime library at $RTLIB" >&2; exit 2; }

W="$(mktemp -d "${TMPDIR:-/tmp}/mini-e2e.XXXXXX")"
[ "${E2E_KEEP:-0}" = 1 ] || trap 'rm -rf "$W"' EXIT
STD="$(find "$ROOT/stdlib" -name '*.kt' | LC_ALL=C sort | tr '\n' ' ')"
# the standard library of the compiler under test: the precompiled lib/stdlib.mlib (default when there is one), or its sources
# (E2E_STDLIB=source: the mode of the bootstrap)
if [ "${E2E_STDLIB:-mlib}" = mlib ] && [ -f "$NATIVE/lib/stdlib.mlib" ]; then
    STDMODE=mlib
    echo "--lib $NATIVE/lib/stdlib.mlib --compiler-id $(cat "$NATIVE/VERSION" 2>/dev/null || echo unknown)" > "$W/stdlib.txt"
else
    STDMODE=source
    echo "--stdlib $STD" > "$W/stdlib.txt"
fi
mkdir -p "$W/res"

# a C compiler driver that can link the sanitizer runtimes (clang here often cannot; gcc is the fallback), or empty
SANCC=""
echo 'int main(void){return 0;}' > "$W/probe.c"
for cand in "$CC" clang gcc; do
    command -v "$cand" >/dev/null 2>&1 || continue
    if "$cand" -fsanitize=address,undefined "$W/probe.c" -o "$W/probe" >/dev/null 2>&1; then SANCC="$cand"; break; fi
done
ASAN_LEAKS=1
[ "$(uname -s)" = Darwin ] && ASAN_LEAKS=0   # LeakSanitizer does not exist on macOS: there the run still checks memory errors and UB

# the generated C has unused locals, labels and temporaries by design (the same flags the old JVM driver used)
WNO="-Wno-unused-label -Wno-unused-variable -Wno-unused-but-set-variable"
SAN_FLAGS="-fsanitize=address,undefined -fno-sanitize-recover=undefined -g -fno-omit-frame-pointer"

# runtime objects built once per flavour
build_rt() { # <dir> <cc> <flags...>
    local dir="$1" cc="$2"; shift 2
    mkdir -p "$dir"
    export RT_BUILD_DIR="$dir" RT_BUILD_CC="$cc" RT_BUILD_FLAGS="$*"
    export ROOT INCLUDE
    find "$ROOT/runtime/src" -maxdepth 1 -name '*.c' -print0 |
        xargs -0 -P "$JOBS" /bin/sh -c '
            for f do
                "$RT_BUILD_CC" -std=c17 -O1 -w -I "$INCLUDE" $RT_BUILD_FLAGS -c "$f" -o "$RT_BUILD_DIR/$(basename "$f" .c).o" || exit
            done
        ' sh
}

run_with_timeout() { # <seconds> <command> [args...]
    local seconds="$1"; shift
    if [ -n "$TIMEOUT_CMD" ]; then
        "$TIMEOUT_CMD" "$seconds" "$@"
    else
        python3 - "$seconds" "$@" <<'PY'
import subprocess
import sys

seconds = int(sys.argv[1])
try:
    result = subprocess.run(sys.argv[2:], timeout=seconds)
except subprocess.TimeoutExpired:
    sys.exit(124)
sys.exit(result.returncode)
PY
    fi
}

want_suite() { local s; for s in "${SUITES[@]}"; do [ "$s" = "$1" ] && return 0; done; return 1; }

# ---- test bodies: each prints nothing on success; on failure a message and exit status 1; exit status 77 = skipped ----------

sib() { echo "${1%.kt}.$2"; }                 # sibling file of a source
has_lines_in() { # <file with expected lines> <text file>: every non-blank line of the first occurs in the second
    local line missing=0
    while IFS= read -r line || [ -n "$line" ]; do
        [ -z "${line//[[:space:]]/}" ] && continue
        grep -qF -- "$line" "$2" || { echo "missing: $line"; missing=1; }
    done < "$1"
    return $missing
}
links_of() { grep -o '/\* mini:link [^ ]* \*/' "$1" 2>/dev/null | sed 's|/\* mini:link \([^ ]*\) \*/|-l\1|' | tr '\n' ' '; }
c_extra() { # <src>: the C test library for the interop programs
    if grep -q 'cinterop_lib.h' "$1"; then echo "$TESTDATA/cinterop_lib.c"; fi
}

t_stress_rss() { # <src>: the peak RSS must not grow with the number of rounds (argument: the scale)
    local src="$1" name; name="$(basename "$src" .kt).rss"
    local c="$W/$name.c" log="$W/$name.log" exe="$W/$name.exe"
    compile_to_c "$src" "$c" "$log" || { echo "build failed:"; head -20 "$log"; return 1; }
    "$CC" -std=c17 -O1 -w -I "$INCLUDE" "$c" "$RTLIB" -o "$exe" -lm > "$W/$name.cc" 2>&1 || { echo "C compiler failed:"; head -20 "$W/$name.cc"; return 1; }
    local r1 r4 st
    r1="$(python3 "$ROOT/scripts/peak-rss.py" --timeout 120 --stdout "$W/$name.o1" -- "$exe" 1)"; st=$?
    [ $st -eq 0 ] || { echo "scale 1: exit $st"; return 1; }
    cmp -s "$(sib "$src" out)" "$W/$name.o1" || { echo "scale 1: stdout differs from $(sib "$src" out)"; return 1; }
    r4="$(python3 "$ROOT/scripts/peak-rss.py" --timeout 240 --stdout "$W/$name.o4" -- "$exe" 4)"; st=$?
    [ $st -eq 0 ] || { echo "scale 4: exit $st"; return 1; }
    echo "$name: peak RSS ${r1} KiB at scale 1, ${r4} KiB at scale 4" >> "$W/rss.txt"
    if [ "$r4" -gt $(( r1 + r1 / 4 + 8192 )) ]; then echo "memory grows with the rounds: ${r1} KiB at scale 1, ${r4} KiB at scale 4"; return 1; fi
    if [ -f "$(sib "$src" rss)" ]; then
        local limit; limit="$(tr -d ' \n' < "$(sib "$src" rss)")"
        [ "$r4" -le "$limit" ] || { echo "peak RSS ${r4} KiB exceeds the limit of ${limit} KiB"; return 1; }
    fi
    return 0
}

run_and_compare() { # <name> <src> <exe> <label>: run, compare stdout with .out, stderr with .err, exit status
    local name="$1" src="$2" exe="$3" label="$4" out="$W/$1.stdout" err="$W/$1.stderr" code
    # Strict programs that move an owned value into `spawn { }` are accepted by the checker but not run: the closure that carries the value
    # into the thread is itself reference counted (move capture in escaping lambdas, docs/memory-model.md section 9)
    if [ "${5:-}" = skip-threads ] && grep -q 'spawn *{' "$src"; then return 77; fi
    ( cd "$W" && run_with_timeout 60 "$exe" > "$out" 2> "$err" ); code=$?
    if [ $code -eq 124 ]; then echo "$label: timed out"; return 1; fi
    if grep -q "threads are not supported yet" "$err" 2>/dev/null && [ "$5" = skip-threads ]; then return 77; fi
    if ! cmp -s "$(sib "$src" out)" "$out"; then echo "$label: stdout differs from $(sib "$src" out)"; diff "$(sib "$src" out)" "$out" | head -10; return 1; fi
    if [ -f "$(sib "$src" err)" ]; then
        [ $code -ne 0 ] || { echo "$label: should have failed at run time"; return 1; }
        has_lines_in "$(sib "$src" err)" "$err" > "$W/$name.m" || { echo "$label: runtime stderr lacks:"; cat "$W/$name.m"; head -5 "$err"; return 1; }
    else
        [ $code -eq 0 ] || { echo "$label: exit $code"; head -10 "$err"; return 1; }
    fi
    return 0
}

compile_to_c() { # <src> <out.c> <log>: native compiler, C backend
    "$MINIC" -o "$2" "$1" $(cat "$W/stdlib.txt") > "$3" 2>&1
}

t_c() { # <src> [asan]
    local src="$1" mode="${2:-plain}" name; name="$(basename "$src" .kt)"; [ "$mode" = asan ] && name="$name.asan"
    local c="$W/$name.c" log="$W/$name.log" exe="$W/$name.exe" extra; extra="$(c_extra "$src")"
    compile_to_c "$src" "$c" "$log"; local rc=$?
    if [ "$mode" = plain ] && [ -f "$(sib "$src" cerr)" ]; then
        [ $rc -eq 1 ] || { echo "expected a compile error (exit 1), got $rc"; head -5 "$log"; return 1; }
        has_lines_in "$(sib "$src" cerr)" "$log" > "$W/$name.m" || { echo "compile output lacks:"; cat "$W/$name.m"; head -5 "$log"; return 1; }
        return 0
    fi
    [ $rc -eq 0 ] || { echo "build failed:"; head -20 "$log"; return 1; }
    local links; links="$(links_of "$c")"
    if [ "$mode" = asan ]; then
        # shellcheck disable=SC2086
        "$SANCC" -std=c17 -O1 -w $SAN_FLAGS -I "$INCLUDE" -I "$TESTDATA" "$c" $extra "$W/rt-asan"/*.o -o "$exe" -lm $links > "$W/$name.cc" 2>&1 || { echo "sanitizer build failed:"; head -20 "$W/$name.cc"; return 1; }
        local out="$W/$name.stdout" err="$W/$name.stderr" code
        ( cd "$W" && ASAN_OPTIONS="detect_leaks=$ASAN_LEAKS:exitcode=99" run_with_timeout 120 "$exe" > "$out" 2> "$err" ); code=$?
        [ $code -eq 0 ] || { echo "failed under sanitizers (exit $code; leak or memory error):"; head -30 "$err"; return 1; }
        cmp -s "$(sib "$src" out)" "$out" || { echo "stdout differs under sanitizers"; diff "$(sib "$src" out)" "$out" | head; return 1; }
        return 0
    fi
    # shellcheck disable=SC2086
    "$CC" -std=c17 -O1 -Wall $WNO -Werror -I "$INCLUDE" -I "$TESTDATA" "$c" $extra "$RTLIB" -o "$exe" -lm $links > "$W/$name.cc" 2>&1 || { echo "C compiler failed (-Wall -Werror):"; head -20 "$W/$name.cc"; return 1; }
    run_and_compare "$name" "$src" "$exe" "c" none
}

t_llvm() { # <src> [asan]
    local src="$1" mode="${2:-plain}" name; name="$(basename "$src" .kt)"; [ "$mode" = asan ] && name="$name.asan"
    local ll="$W/$name.ll" log="$W/$name.log" exe="$W/$name.exe" extra; extra="$(c_extra "$src")"
    "$MINIC" --emit-ll "$ll" "$src" $(cat "$W/stdlib.txt") > "$log" 2>&1; local rc=$?
    if [ "$mode" = plain ] && [ -f "$(sib "$src" cerr)" ]; then
        [ $rc -eq 1 ] || { echo "expected a compile error (exit 1), got $rc"; head -5 "$log"; return 1; }
        has_lines_in "$(sib "$src" cerr)" "$log" > "$W/$name.m" || { echo "compile output lacks:"; cat "$W/$name.m"; return 1; }
        return 0
    fi
    [ $rc -eq 0 ] || { echo "LLVM build failed:"; head -20 "$log"; return 1; }
    local links; links="$(links_of "$ll")"
    if [ "$mode" = asan ]; then
        # shellcheck disable=SC2086
        clang -O1 -w $SAN_FLAGS -Wno-override-module -c "$ll" -o "$W/$name.o" > "$W/$name.cc" 2>&1 || { echo "clang on the IR failed:"; head -20 "$W/$name.cc"; return 1; }
        # shellcheck disable=SC2086
        "$SANCC" $SAN_FLAGS -I "$INCLUDE" -I "$TESTDATA" "$W/$name.o" $extra "$W/rt-asan"/*.o -o "$exe" -lm $links >> "$W/$name.cc" 2>&1 || { echo "sanitizer link failed:"; head -20 "$W/$name.cc"; return 1; }
        local out="$W/$name.stdout" err="$W/$name.stderr" code
        ( cd "$W" && ASAN_OPTIONS="detect_leaks=$ASAN_LEAKS:exitcode=99" run_with_timeout 120 "$exe" > "$out" 2> "$err" ); code=$?
        [ $code -eq 0 ] || { echo "failed under sanitizers (exit $code):"; head -30 "$err"; return 1; }
        cmp -s "$(sib "$src" out)" "$out" || { echo "stdout differs under sanitizers"; return 1; }
        return 0
    fi
    # shellcheck disable=SC2086
    clang -O1 -Wall $WNO -Werror -Wno-override-module -Wno-unused-command-line-argument -I "$INCLUDE" -I "$TESTDATA" "$ll" $extra "$RTLIB" -o "$exe" -lm $links > "$W/$name.cc" 2>&1 || { echo "clang failed on the IR:"; head -20 "$W/$name.cc"; return 1; }
    run_and_compare "$name" "$src" "$exe" "llvm" none
}

t_llvm_variant() { # <flag>: rc_stress through native-driver with the flag
    local flag="$1" src="$TESTDATA/rc_stress.kt" name="rc_stress_v${1//-/_}"
    local drv="${NATIVE_DRIVER:-}"
    [ -x "$drv" ] || { echo "NATIVE_DRIVER not set"; return 77; }
    "$MINIC" --emit-mir "$W/$name.mir" "$src" $(cat "$W/stdlib.txt") > "$W/$name.log" 2>&1 || { head "$W/$name.log"; return 1; }
    "$drv" "$W/$name.mir" --backend=llvm "$flag" -o "$W/$name.ll" > "$W/$name.drv" 2>&1 || { echo "native-driver failed:"; head "$W/$name.drv"; return 1; }
    local rt="$RTLIB"
    [ "$flag" = --stats ] && rt="$W/rt-stats/*.o"
    # shellcheck disable=SC2086
    clang -O1 -w -Wno-override-module -I "$INCLUDE" "$W/$name.ll" $rt -o "$W/$name.exe" -lm > "$W/$name.cc" 2>&1 || { echo "clang failed:"; head "$W/$name.cc"; return 1; }
    ( cd "$W" && run_with_timeout 60 "$W/$name.exe" > "$W/$name.stdout" 2> "$W/$name.stderr" ) || { echo "exit status"; head "$W/$name.stderr"; return 1; }
    cmp -s "$(sib "$src" out)" "$W/$name.stdout" || { echo "stdout differs"; return 1; }
    if [ "$flag" = --stats ]; then grep -q "mk-stats:" "$W/$name.stderr" || { echo "no MK_STATS summary on stderr"; return 1; }; fi
    return 0
}

t_llvm_api() { # <src>
    local src="$1" name; name="$(basename "$src" .kt)"
    local api="${MINIC_API:-}" extra; extra="$(c_extra "$src")"
    [ -x "$api" ] || { echo "MINIC_API not set"; return 77; }
    "$api" "$src" --backend=llvm-api -o "$W/$name.o" $(cat "$W/stdlib.txt") > "$W/$name.log" 2>&1; local rc=$?
    if [ -f "$(sib "$src" cerr)" ]; then
        [ $rc -ne 0 ] || { echo "expected a compile error"; return 1; }
        has_lines_in "$(sib "$src" cerr)" "$W/$name.log" > "$W/$name.m" || { echo "output lacks:"; cat "$W/$name.m"; return 1; }
        return 0
    fi
    [ $rc -eq 0 ] || { echo "API build failed:"; head -20 "$W/$name.log"; return 1; }
    local links; links="$(links_of "$src")"
    # shellcheck disable=SC2086
    clang -I "$INCLUDE" -I "$TESTDATA" "$W/$name.o" $extra "$RTLIB" -o "$W/$name.exe" -lm > "$W/$name.cc" 2>&1 || { echo "link failed:"; head "$W/$name.cc"; return 1; }
    run_and_compare "$name" "$src" "$W/$name.exe" "llvm-api" none
}

t_memcheck() { # <src>
    local src="$1" name; name="$(basename "$src" .kt)-$$-$RANDOM"
    "$MINIC" --check --brief "$src" $(cat "$W/stdlib.txt") > "$W/$name.out" 2> "$W/$name.err"
    local want="$W/$name.want"
    if [ -f "$(sib "$src" diag)" ]; then sed 's/^[[:space:]]*//;s/[[:space:]]*$//' "$(sib "$src" diag)" | grep -v '^$' | LC_ALL=C sort > "$want"; else : > "$want"; fi
    LC_ALL=C sort "$W/$name.out" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$' > "$W/$name.got" || true
    cmp -s "$want" "$W/$name.got" || { echo "diagnostics differ (expected < > got):"; diff "$want" "$W/$name.got" | head -10; head -3 "$W/$name.err"; return 1; }
}

t_strict() { # <src>: the verdict is the first line of the program
    local src="$1" name; name="st_$(basename "$src" .kt)"
    local h; h="$(head -1 "$src")"
    local got; got="$("$MINIC" --check --brief "$src" $(cat "$W/stdlib.txt") 2>&1 | grep -o 'M0[0-9]*' | LC_ALL=C sort -u | tr '\n' ' ' | sed 's/ $//')"
    case "$h" in
        "// REJECT"*)
            local want; want="$(echo "$h" | grep -o 'M0[0-9]*' | head -1)"
            [ "$got" = "$want" ] || { echo "expected only $want, got: ${got:-no diagnostic}"; return 1; }
            return 0 ;;
        *) [ -z "$got" ] || { echo "a correct program was rejected: $got"; "$MINIC" --check "$src" $(cat "$W/stdlib.txt") 2>&1 | head -12; return 1; } ;;
    esac
    [ -f "${src%.kt}.out" ] || return 0
    "$MINIC" -o "$W/$name.c" "$src" $(cat "$W/stdlib.txt") > "$W/$name.log" 2>&1 || { echo "build failed:"; head -10 "$W/$name.log"; return 1; }
    local links; links="$(links_of "$W/$name.c")"
    # shellcheck disable=SC2086
    "$CC" -std=c17 -O1 -w -I "$INCLUDE" "$W/$name.c" "$RTLIB" -o "$W/$name.exe" -lm $links > "$W/$name.cc" 2>&1 || { echo "C compiler failed:"; head "$W/$name.cc"; return 1; }
    run_and_compare "$name" "$src" "$W/$name.exe" "strict" none
}

t_memrun() { # <src>
    local src="$1" name; name="mr_$(basename "$src" .kt)"
    "$MINIC" -o "$W/$name.c" "$src" $(cat "$W/stdlib.txt") > "$W/$name.log" 2>&1; local rc=$?
    if [ $rc -ne 0 ]; then
        grep -q "unsupported construct" "$W/$name.log" && return 77
        echo "build failed:"; head -10 "$W/$name.log"; return 1
    fi
    local links; links="$(links_of "$W/$name.c")"
    # shellcheck disable=SC2086
    "$CC" -std=c17 -O1 -w -I "$INCLUDE" "$W/$name.c" "$RTLIB" -o "$W/$name.exe" -lm $links > "$W/$name.cc" 2>&1 || { echo "C compiler failed:"; head "$W/$name.cc"; return 1; }
    run_and_compare "$name" "$src" "$W/$name.exe" "memrun" skip-threads
}

t_multifile() {
    local d="$W/multi"; mkdir -p "$d"
    printf 'fun main() {\n    println(greet("mini"))\n    println(Counter().next() + Counter().next())\n}\n' > "$d/a.kt"
    printf 'fun greet(name: String): String = "Hello, " + name.uppercase().padEnd(6, '"'"'!'"'"')\nclass Counter {\n    private var n = 0\n    fun next(): Int { n++; return n }\n}\n' > "$d/b.kt"
    "$MINIC" -o "$d/out.c" "$d/a.kt" "$d/b.kt" $(cat "$W/stdlib.txt") > "$d/log" 2>&1 || { echo "build failed:"; head "$d/log"; return 1; }
    "$CC" -std=c17 -O1 -Wall $WNO -Werror -I "$INCLUDE" "$d/out.c" "$RTLIB" -o "$d/out" -lm > "$d/cc" 2>&1 || { echo "C compiler failed:"; head "$d/cc"; return 1; }
    [ "$("$d/out")" = "$(printf 'Hello, MINI!!\n2')" ] || { echo "wrong output"; return 1; }
    grep -q "ArrayList" "$d/out.c" && { echo "unreachable stdlib code was emitted"; return 1; }
    grep -q "kt_kotlin_Dtext_Duppercase_AS" "$d/out.c" || { echo "reachable stdlib code is missing"; return 1; }
    return 0
}

# a class chain spread over three files, with an unreachable sibling subclass: the layout depends on the class chain alone, so pruning
# classes must not change the offsets of the others
t_multifile_layout() {
    local d="$W/multi-layout"; mkdir -p "$d"
    cat > "$d/a.kt" <<'EOF'
fun main() {
    val c = Leaf(1L shl 34, 2, true, 3, 4, "leaf", 5, 6L shl 34, null)
    println(c)
    c.mid2 = 9; c.flagA = false; c.last = 7
    println(c)
    val m: Mid = c
    println(m.sum())
}
EOF
    cat > "$d/b.kt" <<'EOF'
open class Root(var big: Long, var n: Int) {
    override fun toString(): String = "Root($big,$n)"
}

open class Mid(big: Long, n: Int, var flagA: Boolean, var mid2: Short, var mid3: Byte, var name: String) : Root(big, n) {
    fun sum(): Long = big + n + (if (flagA) 100 else 0) + mid2 + mid3
}

class Pruned(big: Long, n: Int, var x: Long, var y: Boolean) : Root(big, n)
EOF
    cat > "$d/c.kt" <<'EOF'
class Leaf(big: Long, n: Int, flagA: Boolean, mid2: Short, mid3: Byte, name: String, var last: Int, var other: Long, var link: Root?) :
    Mid(big, n, flagA, mid2, mid3, name) {
    override fun toString(): String = "Leaf($big,$n,$flagA,$mid2,$mid3,$name,$last,$other,$link)"
}
EOF
    "$MINIC" -o "$d/out.c" "$d/a.kt" "$d/b.kt" "$d/c.kt" $(cat "$W/stdlib.txt") > "$d/log" 2>&1 || { echo "build failed:"; head "$d/log"; return 1; }
    "$CC" -std=c17 -O1 -Wall $WNO -Werror -I "$INCLUDE" "$d/out.c" "$RTLIB" -o "$d/out" -lm > "$d/cc" 2>&1 || { echo "C compiler failed:"; head "$d/cc"; return 1; }
    [ "$("$d/out")" = "$(printf 'Leaf(17179869184,2,true,3,4,leaf,5,103079215104,null)\nLeaf(17179869184,2,false,9,4,leaf,7,103079215104,null)\n17179869199')" ] || { echo "wrong output: $("$d/out")"; return 1; }
    grep -q "struct Pruned" "$d/out.c" && { echo "the unreachable class was emitted"; return 1; }
    return 0
}

t_allfiles() { # the memory checker looks at every user file, not only the first
    local d="$W/allfiles"; mkdir -p "$d"
    printf '@file:MemoryMode(Mode.Strict)\nimport mini.memory.*\n\n@AllocatedBy(Heap::class)\nclass Box(val v: Int)\n\nfun main() {\n    val b = Box(1)\n    b.free()\n    leak()\n}\n' > "$d/a.kt"
    printf '@file:MemoryMode(Mode.Strict)\nimport mini.memory.*\n\nfun leak() {\n    val c = Box(2)\n    println(c.v)\n}\n' > "$d/b.kt"
    "$MINIC" --check "$d/a.kt" "$d/b.kt" $(cat "$W/stdlib.txt") > "$d/chk" 2>&1; local rc=$?
    [ $rc -eq 1 ] || { echo "the leak in the second file must be reported (exit $rc)"; head "$d/chk"; return 1; }
    grep -q "M0102" "$d/chk" && grep -q "b.kt:" "$d/chk" || { echo "diagnostic of b.kt expected"; head "$d/chk"; return 1; }
    "$MINIC" -o "$d/prog.c" "$d/a.kt" "$d/b.kt" $(cat "$W/stdlib.txt") > "$d/bld" 2>&1; rc=$?
    [ $rc -eq 1 ] && grep -q "M0102" "$d/bld" || { echo "build must fail on the memory error in b.kt (exit $rc)"; head "$d/bld"; return 1; }
    # the checker rejects and accepts
    "$MINIC" --check "$TESTDATA/memory/uaf_simple.kt" $(cat "$W/stdlib.txt") > /dev/null 2>&1 && { echo "uaf_simple.kt must be rejected"; return 1; }
    "$MINIC" --check "$TESTDATA/memory/ok_heap_basic.kt" $(cat "$W/stdlib.txt") > /dev/null 2>&1 || { echo "ok_heap_basic.kt must be accepted"; return 1; }
    return 0
}

# ---- precompiled libraries (docs/mlib.md) -------------------------------------------------------------------------------------

# two libraries (b depends on a, both with lambdas and an object), a program that uses b: the output must be right and the generated
# names of the two libraries must not collide
t_mlib_chain() {
    [ "$STDMODE" = mlib ] || return 77
    local d="$W/mlib-chain"; mkdir -p "$d"
    local S="$NATIVE/lib/stdlib.mlib" V; V="$(cat "$NATIVE/VERSION" 2>/dev/null || echo unknown)"
    printf 'package a\n\nfun twice(f: (Int) -> Int, x: Int): Int = f(f(x))\nfun addAll(xs: List<Int>): Int { var s = 0; xs.forEach { s += it }; return s }\nobject Registry { val names = mutableListOf<String>() }\nval greeting = "hi " + "there"\n' > "$d/a.kt"
    printf 'package b\n\nimport a.*\n\nfun total(n: Int): Int = addAll((1..n).map { twice({ v -> v * 2 }, it) })\nfun hello(): String { Registry.names.add("x"); return greeting + Registry.names.size }\ninline fun <T> run2(f: () -> T): T = f()\n' > "$d/b.kt"
    printf 'import b.*\n\nfun main() {\n    println(total(4))\n    println(hello())\n    println(run2 { 7 })\n}\n' > "$d/main.kt"
    "$MINIC" --emit-mlib "$d/a.mlib" --name a --lib-version 1.0.0 --compiler-id "$V" --lib "$S" "$d/a.kt" > "$d/log" 2>&1 || { echo "a.mlib failed:"; head "$d/log"; return 1; }
    "$MINIC" --emit-mlib "$d/b.mlib" --name b --compiler-id "$V" --lib "$S" --lib "$d/a.mlib" "$d/b.kt" > "$d/log" 2>&1 || { echo "b.mlib failed:"; head "$d/log"; return 1; }
    "$MINIC" -o "$d/out.c" "$d/main.kt" --lib "$S" --lib "$d/b.mlib" --lib "$d/a.mlib" --compiler-id "$V" > "$d/log" 2>&1 || { echo "program failed:"; head "$d/log"; return 1; }
    "$CC" -std=c17 -O1 -w -I "$INCLUDE" "$d/out.c" "$RTLIB" -o "$d/out" -lm > "$d/cc" 2>&1 || { echo "C compiler failed:"; head "$d/cc"; return 1; }
    [ "$("$d/out")" = "$(printf '40\nhi there1\n7')" ] || { echo "wrong output: $("$d/out")"; return 1; }
    # the same program in source mode agrees
    "$MINIC" -o "$d/src.c" "$d/main.kt" "$d/a.kt" "$d/b.kt" --stdlib $STD > "$d/log" 2>&1 || { echo "source mode failed:"; head "$d/log"; return 1; }
    "$CC" -std=c17 -O1 -w -I "$INCLUDE" "$d/src.c" "$RTLIB" -o "$d/src" -lm > "$d/cc" 2>&1 || { echo "C compiler failed:"; head "$d/cc"; return 1; }
    [ "$("$d/src")" = "$("$d/out")" ] || { echo "source mode and library mode differ"; return 1; }
    # the dependency is needed, and it must be the one b was built against
    "$MINIC" --check "$d/main.kt" --lib "$S" --lib "$d/b.mlib" > "$d/log" 2>&1 && { echo "missing dependency a was not reported"; return 1; }
    grep -q "needs library 'a'" "$d/log" || { echo "wrong message for a missing dependency:"; head "$d/log"; return 1; }
    printf 'package a\n\nfun twice(f: (Int) -> Int, x: Int): Int = f(x)\n' > "$d/a2.kt"
    "$MINIC" --emit-mlib "$d/a2.mlib" --name a --compiler-id "$V" --lib "$S" "$d/a2.kt" > "$d/log" 2>&1 || { echo "a2.mlib failed:"; head "$d/log"; return 1; }
    "$MINIC" --check "$d/main.kt" --lib "$S" --lib "$d/b.mlib" --lib "$d/a2.mlib" > "$d/log" 2>&1 && { echo "wrong dependency hash was not reported"; return 1; }
    grep -q "was built against 'a'" "$d/log" || { echo "wrong message for a wrong dependency:"; head "$d/log"; return 1; }
    return 0
}

# object layout across precompiled libraries (docs/memory-model.md, "Object layout"): a class chain whose levels live in two libraries and
# the program (a subclass fills the tail padding of its superclass; an exception chain; a data class) must lay out identically in library
# mode and in source mode, and the fields of all levels must stay independent
t_mlib_layout() {
    [ "$STDMODE" = mlib ] || return 77
    local d="$W/mlib-layout"; mkdir -p "$d"
    local S="$NATIVE/lib/stdlib.mlib" V; V="$(cat "$NATIVE/VERSION" 2>/dev/null || echo unknown)"
    cat > "$d/a.kt" <<'EOF'
package base

open class Animal(var weight: Long, var legs: Int) {
    open fun describe(): String = "Animal($weight,$legs)"
}

open class BaseError(message: String, val code: Int, val fatal: Boolean) : Exception(message) {
    var attempts: Long = 3L
}

data class Key(val on: Boolean, val id: Int, val big: Long, val name: String)
EOF
    cat > "$d/b.kt" <<'EOF'
package mid

import base.*

open class Dog(weight: Long, legs: Int, var tame: Boolean, var age: Short, var tag: Byte, var name: String) : Animal(weight, legs) {
    override fun describe(): String = "Dog($weight,$legs,$tame,$age,$tag,$name)"
}

class MidError(message: String, code: Int, val host: String, val retry: Boolean) : BaseError(message, code, false) {
    var bytes: Long = -1L
}

fun mkKey(i: Int): Key = Key(i % 2 == 0, i, i.toLong() shl 33, "k$i")
EOF
    cat > "$d/main.kt" <<'EOF'
import base.*
import mid.*

class Puppy(weight: Long, legs: Int, tame: Boolean, age: Short, tag: Byte, name: String, var months: Int, var big: Long, var flag: Boolean, var mom: Dog?) :
    Dog(weight, legs, tame, age, tag, name) {
    override fun describe(): String = "Puppy($weight,$legs,$tame,$age,$tag,$name,$months,$big,$flag,${mom?.describe()})"
}

class AppError(host: String, val millis: Long, val soft: Boolean) : MidError("app", 7, host, true) {
    var tries: Int = 2
}

fun main() {
    val dog = Dog(5L shl 32, 4, true, -3, 9, "rex")
    val pup = Puppy(1L shl 33, 4, false, 2, -5, "bit", 7, 1L shl 40, true, dog)
    println(dog.describe())
    println(pup.describe())
    val all: List<Animal> = listOf(Animal(1, 2), dog, pup)
    println(all.map { it.describe() })
    pup.legs = 3; pup.tame = true; pup.months += 1; pup.flag = false
    println(pup.describe())
    try {
        throw AppError("h.example", 1L shl 35, true)
    } catch (e: MidError) {
        val a = e as AppError
        println("${e.message} ${e.code} ${e.fatal} ${e.attempts} ${e.host} ${e.retry} ${e.bytes} ${a.millis} ${a.soft} ${a.tries}")
    }
    val k = mkKey(3)
    println(k)
    println(k == k.copy(id = 3))
    println(k.copy(on = true) == k)
    println(k.hashCode() == mkKey(3).hashCode())
}
EOF
    cat > "$d/expected.txt" <<'EOF'
Dog(21474836480,4,true,-3,9,rex)
Puppy(8589934592,4,false,2,-5,bit,7,1099511627776,true,Dog(21474836480,4,true,-3,9,rex))
[Animal(1,2), Dog(21474836480,4,true,-3,9,rex), Puppy(8589934592,4,false,2,-5,bit,7,1099511627776,true,Dog(21474836480,4,true,-3,9,rex))]
Puppy(8589934592,3,true,2,-5,bit,8,1099511627776,false,Dog(21474836480,4,true,-3,9,rex))
app 7 false 3 h.example true -1 34359738368 true 2
Key(on=false, id=3, big=25769803776, name=k3)
true
false
true
EOF
    "$MINIC" --emit-mlib "$d/a.mlib" --name base --lib-version 1.0.0 --compiler-id "$V" --lib "$S" "$d/a.kt" > "$d/log" 2>&1 || { echo "a.mlib failed:"; head "$d/log"; return 1; }
    "$MINIC" --emit-mlib "$d/b.mlib" --name mid --compiler-id "$V" --lib "$S" --lib "$d/a.mlib" "$d/b.kt" > "$d/log" 2>&1 || { echo "b.mlib failed:"; head "$d/log"; return 1; }
    "$MINIC" -o "$d/out.c" "$d/main.kt" --lib "$S" --lib "$d/b.mlib" --lib "$d/a.mlib" --compiler-id "$V" > "$d/log" 2>&1 || { echo "program failed:"; head "$d/log"; return 1; }
    "$CC" -std=c17 -O1 -Wall $WNO -Werror -I "$INCLUDE" "$d/out.c" "$RTLIB" -o "$d/out" -lm > "$d/cc" 2>&1 || { echo "C compiler failed:"; head "$d/cc"; return 1; }
    "$d/out" > "$d/out.txt" 2>&1 || { echo "program failed to run:"; head "$d/out.txt"; return 1; }
    "$MINIC" -o "$d/src.c" "$d/main.kt" "$d/a.kt" "$d/b.kt" --stdlib $STD > "$d/log" 2>&1 || { echo "source mode failed:"; head "$d/log"; return 1; }
    "$CC" -std=c17 -O1 -Wall $WNO -Werror -I "$INCLUDE" "$d/src.c" "$RTLIB" -o "$d/src" -lm > "$d/cc" 2>&1 || { echo "C compiler failed:"; head "$d/cc"; return 1; }
    "$d/src" > "$d/src.txt" 2>&1 || { echo "source-mode program failed to run:"; head "$d/src.txt"; return 1; }
    cmp -s "$d/out.txt" "$d/src.txt" || { echo "source mode and library mode differ"; diff "$d/out.txt" "$d/src.txt" | head; return 1; }
    cmp -s "$d/out.txt" "$d/expected.txt" || { echo "wrong output:"; diff "$d/out.txt" "$d/expected.txt" | head; return 1; }
    grep -q "_Static_assert(offsetof(struct" "$d/out.c" || { echo "no layout asserts in the program C"; return 1; }
    return 0
}

# the inferred summaries travel in the library (docs/mlib.md): a Strict program is checked precisely against an unannotated library
t_mlib_summaries() {
    [ "$STDMODE" = mlib ] || return 77
    local d="$W/mlib-summaries"; mkdir -p "$d"
    local S="$NATIVE/lib/stdlib.mlib" V; V="$(cat "$NATIVE/VERSION" 2>/dev/null || echo unknown)"
    printf 'package geo\n\nclass Keeper { var kept: Any? = null }\n\nobject Util {\n    fun describe(o: Any): String = "obj:" + o.toString()\n    fun keep(k: Keeper, o: Any) { k.kept = o }\n}\n' > "$d/lib.kt"
    local pre='@file:MemoryMode(Mode.Strict)\n\nimport mini.memory.*\nimport geo.*\n\n@AllocatedBy(Heap::class)\nclass Box(val v: Int)\n\n'
    printf "${pre}fun main() {\n    val b = Box(1)\n    println(Util.describe(b).length > 0)\n    b.free()\n}\n" > "$d/ok.kt"
    printf "${pre}fun main() {\n    val k = Keeper()\n    val b = Box(1)\n    Util.keep(k, b)\n    b.free()\n}\n" > "$d/bad.kt"
    "$MINIC" --emit-mlib "$d/geo.mlib" --name geo --compiler-id "$V" --lib "$S" "$d/lib.kt" > "$d/log" 2>&1 || { echo "geo.mlib failed:"; head "$d/log"; return 1; }
    grep -aq '^geo.Util.keep/' "$d/geo.mlib" || { echo "no summary for Util.keep in the library"; return 1; }
    "$MINIC" --check "$d/ok.kt" --lib "$S" --lib "$d/geo.mlib" --compiler-id "$V" > "$d/log" 2>&1 || { echo "a borrowing library call was rejected:"; head "$d/log"; return 1; }
    "$MINIC" --check --brief "$d/bad.kt" --lib "$S" --lib "$d/geo.mlib" --compiler-id "$V" > "$d/log" 2>&1 && { echo "a library call that keeps its argument was accepted"; return 1; }
    grep -q "M0110" "$d/log" || { echo "wrong diagnostic:"; head "$d/log"; return 1; }
    # a Strict library function seen through its stub: the stored summary says which function-value parameters it keeps
    printf '@file:MemoryMode(Mode.Strict)\npackage sgeo\n\nimport mini.memory.*\n\nclass Holder { var f: (() -> Int)? = null }\nfun keep(h: Holder, f: () -> Int) { h.f = f }\nfun twice(f: () -> Int): Int = f() + f()\nfun scaled(k: Int, f: (Int) -> Int): () -> Int = { f(k) }\n' > "$d/slib.kt"
    local spre='@file:MemoryMode(Mode.Strict)\n\nimport mini.memory.*\nimport sgeo.*\n\n@AllocatedBy(Heap::class)\nclass Box(val v: Int)\n\n'
    printf "${spre}fun main() {\n    val b = Box(2)\n    println(twice { b.v })\n    val c = scaled(3) { it + b.v }\n    println(c())\n    b.free()\n}\n" > "$d/sok.kt"
    printf "${spre}fun main() {\n    val b = Box(2)\n    val h = Holder()\n    keep(h) { b.v }\n    b.free()\n}\n" > "$d/sbad.kt"
    printf "${spre}fun main() {\n    val b = Box(2)\n    val c = scaled(3) { it + b.v }\n    b.free()\n    println(c())\n}\n" > "$d/sbad2.kt"
    "$MINIC" --emit-mlib "$d/sgeo.mlib" --name sgeo --compiler-id "$V" --lib "$S" "$d/slib.kt" > "$d/log" 2>&1 || { echo "sgeo.mlib failed:"; head "$d/log"; return 1; }
    "$MINIC" --check "$d/sok.kt" --lib "$S" --lib "$d/sgeo.mlib" --compiler-id "$V" > "$d/log" 2>&1 || { echo "a closure passed to a Strict library function that does not keep it was rejected:"; head "$d/log"; return 1; }
    "$MINIC" --check --brief "$d/sbad.kt" --lib "$S" --lib "$d/sgeo.mlib" --compiler-id "$V" > "$d/log" 2>&1 && { echo "a closure kept by a Strict library function was accepted"; return 1; }
    grep -q "M0112" "$d/log" || { echo "wrong diagnostic (sbad):"; head "$d/log"; return 1; }
    "$MINIC" --check --brief "$d/sbad2.kt" --lib "$S" --lib "$d/sgeo.mlib" --compiler-id "$V" > "$d/log" 2>&1 && { echo "a closure returned over an owner that was freed was accepted"; return 1; }
    grep -q "M0101" "$d/log" || { echo "wrong diagnostic (sbad2):"; head "$d/log"; return 1; }
    return 0
}

# clear errors for a library that is not one, is damaged, has another format version or comes from another compiler
t_mlib_errors() {
    [ "$STDMODE" = mlib ] || return 77
    local d="$W/mlib-errors"; mkdir -p "$d"
    local S="$NATIVE/lib/stdlib.mlib" V; V="$(cat "$NATIVE/VERSION" 2>/dev/null || echo unknown)"
    printf 'fun main() { println(1) }\n' > "$d/m.kt"
    echo "not a library" > "$d/junk.mlib"
    "$MINIC" --check "$d/m.kt" --lib "$d/junk.mlib" > "$d/log" 2>&1 && { echo "junk accepted"; return 1; }
    grep -q "not a Mini library" "$d/log" || { echo "junk: wrong message"; head "$d/log"; return 1; }
    sed '1s/.*/mini-mlib 99/' "$S" > "$d/future.mlib"
    "$MINIC" --check "$d/m.kt" --lib "$d/future.mlib" > "$d/log" 2>&1 && { echo "another format version accepted"; return 1; }
    grep -q "format version 99" "$d/log" || { echo "version: wrong message"; head "$d/log"; return 1; }
    sed 's/^name stdlib$/name stdlibx/' "$S" > "$d/edited.mlib"
    "$MINIC" --check "$d/m.kt" --lib "$d/edited.mlib" > "$d/log" 2>&1 && { echo "an edited library was accepted"; return 1; }
    grep -q "content hash mismatch" "$d/log" || { echo "hash: wrong message"; head "$d/log"; return 1; }
    head -c 3000 "$S" > "$d/cut.mlib"
    "$MINIC" --check "$d/m.kt" --lib "$d/cut.mlib" > "$d/log" 2>&1 && { echo "a truncated library was accepted"; return 1; }
    grep -q "truncated" "$d/log" || { echo "truncated: wrong message"; head "$d/log"; return 1; }
    "$MINIC" --check "$d/m.kt" --lib "$S" --compiler-id some-other-compiler > "$d/log" 2>&1 && { echo "a library of another compiler was accepted"; return 1; }
    grep -q "was built by compiler" "$d/log" || { echo "compiler id: wrong message"; head "$d/log"; return 1; }
    "$MINIC" --check "$d/m.kt" --lib "$S" --compiler-id "$V" > "$d/log" 2>&1 || { echo "the right library was refused:"; head "$d/log"; return 1; }
    return 0
}

# ---- driver -----------------------------------------------------------------------------------------------------------------------

export STDMODE STD
export WNO ROOT NATIVE MINIC RTLIB INCLUDE TESTDATA CC SANCC ASAN_LEAKS SAN_FLAGS W NATIVE_DRIVER MINIC_API
export -f sib has_lines_in links_of c_extra run_and_compare compile_to_c t_stress_rss t_c t_llvm t_llvm_variant t_llvm_api t_memcheck t_memrun t_strict t_multifile t_multifile_layout t_allfiles t_mlib_chain t_mlib_errors t_mlib_summaries t_mlib_layout run_with_timeout
export TIMEOUT_CMD

# a test list: "<suite> <id> <function and arguments>" per line
LISTFILE="$W/tests.txt"
: > "$LISTFILE"
add() { echo "$1|$2|$3" >> "$LISTFILE"; }
progs() { find "$TESTDATA" -maxdepth 1 -name '*.kt' | LC_ALL=C sort; }
leakable() { # sources with .out and no .err/.cerr
    local d f
    for d in "$TESTDATA" "$TESTDATA/memory"; do
        [ -d "$d" ] || continue
        for f in $(find "$d" -maxdepth 1 -name '*.kt' | LC_ALL=C sort); do
            [ -f "${f%.kt}.out" ] && [ ! -f "${f%.kt}.cerr" ] && [ ! -f "${f%.kt}.err" ] && echo "$f"
        done
    done
}
want_suite c && for f in $(progs); do add c "$(basename "$f" .kt)" "t_c $f"; done
want_suite leak && for f in $(leakable); do add leak "${f#"$TESTDATA"/}" "t_c $f asan"; done
want_suite llvm && for f in $(progs); do add llvm "$(basename "$f" .kt)" "t_llvm $f"; done
want_suite llvm-leak && for f in $(leakable); do add llvm-leak "${f#"$TESTDATA"/}" "t_llvm $f asan"; done
want_suite llvm-variants && for v in --stats --no-rc-opt --no-strict --no-conv; do add llvm-variants "rc_stress $v" "t_llvm_variant $v"; done
want_suite llvm-api && for f in $(progs); do add llvm-api "$(basename "$f" .kt)" "t_llvm_api $f"; done
want_suite memcheck && for f in $(find "$TESTDATA/memory" "$TESTDATA/memory-extra" -maxdepth 1 -name '*.kt' | LC_ALL=C sort); do add memcheck "${f#"$TESTDATA"/}" "t_memcheck $f"; done
want_suite memrun && for f in $(find "$TESTDATA/memory" -maxdepth 1 -name '*.kt' | LC_ALL=C sort); do [ -f "${f%.kt}.out" ] && add memrun "$(basename "$f" .kt)" "t_memrun $f"; done
want_suite strict && for f in $(find "$TESTDATA/strict-corpus" -maxdepth 1 -name '*.kt' | LC_ALL=C sort); do add strict "$(basename "$f" .kt)" "t_strict $f"; done
# the accepted corpus programs that run (closures that borrow their captures, object expressions...) under ASan + LSan, both backends
strict_runnable() { local f; for f in $(find "$TESTDATA/strict-corpus" -maxdepth 1 -name '*.kt' | LC_ALL=C sort); do [ -f "${f%.kt}.out" ] && echo "$f"; done; }
want_suite strict-leak && for f in $(strict_runnable); do add strict-leak "$(basename "$f" .kt)" "t_c $f asan"; done
want_suite strict-llvm-leak && for f in $(strict_runnable); do add strict-llvm-leak "$(basename "$f" .kt)" "t_llvm $f asan"; done
stress_progs() { find "$TESTDATA/stress" -maxdepth 1 -name '*.kt' 2>/dev/null | LC_ALL=C sort; }
want_suite stress-leak && for f in $(stress_progs); do add stress-leak "$(basename "$f" .kt)" "t_c $f asan"; done
want_suite stress-llvm-leak && for f in $(stress_progs); do add stress-llvm-leak "$(basename "$f" .kt)" "t_llvm $f asan"; done
want_suite stress-rss && for f in $(stress_progs); do add stress-rss "$(basename "$f" .kt)" "t_stress_rss $f"; done
want_suite mlib && { add mlib chainOfLibraries t_mlib_chain; add mlib errors t_mlib_errors; add mlib summaries t_mlib_summaries; add mlib objectLayout t_mlib_layout; }
want_suite multifile && { add multifile twoFilesAndTheStdlib t_multifile; add multifile allUserFilesAreChecked t_allfiles; add multifile classChainAcrossFiles t_multifile_layout; }

if [ -n "$FILTER" ]; then grep -F -- "$FILTER" "$LISTFILE" > "$LISTFILE.f" || true; mv "$LISTFILE.f" "$LISTFILE"; fi
total="$(wc -l < "$LISTFILE" | tr -d ' ')"
if [ "$LIST" = 1 ]; then awk -F'|' '{ print $1 "::" $2 }' "$LISTFILE"; echo "$total tests" >&2; exit 0; fi

# runtime objects for the sanitizer / stats flavours, once
if grep -qE '^(leak|llvm-leak|strict-leak|strict-llvm-leak|stress-leak|stress-llvm-leak)\|' "$LISTFILE"; then
    if [ -n "$SANCC" ]; then build_rt "$W/rt-asan" "$SANCC" $SAN_FLAGS; else echo "test-e2e: no C compiler can link the sanitizer runtimes: the leak suites are skipped" >&2; fi
fi
if grep -q '^llvm-variants|' "$LISTFILE"; then build_rt "$W/rt-stats" "$CC" -DMK_STATS; fi

run_one() { # "<suite>|<id>|<cmd>"
    local suite id cmd out rc
    suite="${1%%|*}"; local rest="${1#*|}"; id="${rest%%|*}"; cmd="${rest#*|}"
    case "$suite" in leak|llvm-leak|strict-leak|strict-llvm-leak|stress-leak|stress-llvm-leak) [ -n "$SANCC" ] || { echo "$suite|$id|SKIP|no sanitizer runtime" > "$W/res/$(echo "$suite-$id" | tr '/ ' '__')"; return; } ;; esac
    # shellcheck disable=SC2086
    out="$(eval "$cmd" 2>&1)"; rc=$?
    local f="$W/res/$(echo "$suite-$id" | tr '/ ' '__')"
    if [ $rc -eq 0 ]; then echo "$suite|$id|ok|" > "$f"
    elif [ $rc -eq 77 ]; then echo "$suite|$id|SKIP|$out" > "$f"
    else printf '%s|%s|FAILED|%s\n' "$suite" "$id" "$(printf '%s' "$out" | tr '\n' '\001')" > "$f"; fi
}
export -f run_one

echo "Running $total tests (-j $JOBS, $MINIC)"
tr '\n' '\0' < "$LISTFILE" | xargs -0 -P "$JOBS" -I{} bash -c 'run_one "$1"' _ {}

pass=0 fail=0 skip=0
declare -A bysuite=()
for f in "$W"/res/*; do
    [ -f "$f" ] || continue
    IFS='|' read -r suite id status msg < "$f"
    case "$status" in
        ok) pass=$((pass + 1)); echo "test $suite::$id ... ok" ;;
        SKIP) skip=$((skip + 1)); echo "test $suite::$id ... skipped" ;;
        FAILED) fail=$((fail + 1)); echo "test $suite::$id ... FAILED"; printf '%s\n' "$msg" | tr '\001' '\n' | sed 's/^/    /' ;;
    esac
    bysuite[$suite]=$(( ${bysuite[$suite]:-0} + 1 ))
done
[ -f "$W/rss.txt" ] && { echo; echo "Peak RSS of the stress programs:"; LC_ALL=C sort "$W/rss.txt" | sed 's/^/  /'; }
echo
for s in "${!bysuite[@]}"; do echo "  $s: ${bysuite[$s]}"; done | sort
echo "test result: $([ $fail -eq 0 ] && echo ok || echo FAILED). $pass passed; $fail failed; $skip skipped"
[ $fail -eq 0 ]
