#!/usr/bin/env bash
# End-to-end tests of the native compiler over compiler/testdata (no JVM). One test per program and suite, run in parallel.
#
#   scripts/test-e2e.sh [-j N] [--suite NAME]... [--list] [filter]
#
# Suites (default: c leak memcheck memrun multifile):
#   c          build every compiler/testdata/*.kt through the C backend (cc -std=c17 -Wall -Werror), run it, compare
#                <name>.out (stdout), <name>.err (substrings of stderr; the program must then exit non-zero),
#                <name>.cerr (the build must fail with exit 1 and the output must contain each line)
#   leak       the same programs (and the Strict programs of testdata/memory with a .out) built with AddressSanitizer +
#                LeakSanitizer + UBSan: same output, exit 0, no report. Skipped without a C compiler that links the sanitizers.
#                `<name>.noleakcheck` opts a program out.
#   llvm       like `c`, through the LLVM backend (MIR -> textual LLVM IR -> clang)
#   llvm-leak  the sanitizer run on the LLVM build
#   llvm-variants  --stats, --no-rc-opt, --no-strict and --no-conv through bin/native-driver (needs NATIVE_DRIVER)
#   llvm-api   the in-process LLVM backend (needs a minic-native built with the LLVM C API: MINIC_API, see compiler/bootstrap.sh
#                --backend=llvm-api, and llvm-config)
#   memcheck   `minic-native --check --brief` over testdata/memory and memory-extra equals the .diag files
#   memrun     the accepted programs of testdata/memory (.out) built and run (programs using constructs the C backend cannot
#                lower yet, or threads, are skipped and counted)
#   multifile  several input files, reachability pruning, all user files are memory-checked
#
# Environment: MINI_ROOT (default <repo>/build/native: bin/minic-native, lib/libminirt.a, include/), MINIC_NATIVE, CC,
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
[ ${#SUITES[@]} -gt 0 ] || SUITES=(c leak memcheck memrun multifile)

[ -x "$MINIC" ] || { echo "test-e2e: no native compiler at $MINIC (run ./mini once, or set MINIC_NATIVE)" >&2; exit 2; }
[ -f "$RTLIB" ] || { echo "test-e2e: no runtime library at $RTLIB" >&2; exit 2; }

W="$(mktemp -d "${TMPDIR:-/tmp}/mini-e2e.XXXXXX")"
[ "${E2E_KEEP:-0}" = 1 ] || trap 'rm -rf "$W"' EXIT
STD="$(find "$ROOT/stdlib" -name '*.kt' | LC_ALL=C sort | tr '\n' ' ')"
echo "$STD" > "$W/stdlib.txt"
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
    for f in "$ROOT"/runtime/src/*.c; do
        echo "$cc -std=c17 -O1 -w -I $INCLUDE $* -c $f -o $dir/$(basename "$f" .c).o"
    done | xargs -P "$JOBS" -I{} sh -c '{}'
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

run_and_compare() { # <name> <src> <exe> <label>: run, compare stdout with .out, stderr with .err, exit status
    local name="$1" src="$2" exe="$3" label="$4" out="$W/$1.stdout" err="$W/$1.stderr" code
    ( cd "$W" && timeout 60 "$exe" > "$out" 2> "$err" ); code=$?
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
    "$MINIC" -o "$2" "$1" --stdlib $(cat "$W/stdlib.txt") > "$3" 2>&1
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
        ( cd "$W" && ASAN_OPTIONS="detect_leaks=$ASAN_LEAKS:exitcode=99" timeout 120 "$exe" > "$out" 2> "$err" ); code=$?
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
    "$MINIC" --emit-ll "$ll" "$src" --stdlib $(cat "$W/stdlib.txt") > "$log" 2>&1; local rc=$?
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
        ( cd "$W" && ASAN_OPTIONS="detect_leaks=$ASAN_LEAKS:exitcode=99" timeout 120 "$exe" > "$out" 2> "$err" ); code=$?
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
    "$MINIC" --emit-mir "$W/$name.mir" "$src" --stdlib $(cat "$W/stdlib.txt") > "$W/$name.log" 2>&1 || { head "$W/$name.log"; return 1; }
    "$drv" "$W/$name.mir" --backend=llvm "$flag" -o "$W/$name.ll" > "$W/$name.drv" 2>&1 || { echo "native-driver failed:"; head "$W/$name.drv"; return 1; }
    local rt="$RTLIB"
    [ "$flag" = --stats ] && rt="$W/rt-stats/*.o"
    # shellcheck disable=SC2086
    clang -O1 -w -Wno-override-module -I "$INCLUDE" "$W/$name.ll" $rt -o "$W/$name.exe" -lm > "$W/$name.cc" 2>&1 || { echo "clang failed:"; head "$W/$name.cc"; return 1; }
    ( cd "$W" && timeout 60 "$W/$name.exe" > "$W/$name.stdout" 2> "$W/$name.stderr" ) || { echo "exit status"; head "$W/$name.stderr"; return 1; }
    cmp -s "$(sib "$src" out)" "$W/$name.stdout" || { echo "stdout differs"; return 1; }
    if [ "$flag" = --stats ]; then grep -q "mk-stats:" "$W/$name.stderr" || { echo "no MK_STATS summary on stderr"; return 1; }; fi
    return 0
}

t_llvm_api() { # <src>
    local src="$1" name; name="$(basename "$src" .kt)"
    local api="${MINIC_API:-}" extra; extra="$(c_extra "$src")"
    [ -x "$api" ] || { echo "MINIC_API not set"; return 77; }
    "$api" "$src" --backend=llvm-api -o "$W/$name.o" --stdlib $(cat "$W/stdlib.txt") > "$W/$name.log" 2>&1; local rc=$?
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
    "$MINIC" --check --brief "$src" --stdlib $(cat "$W/stdlib.txt") > "$W/$name.out" 2> "$W/$name.err"
    local want="$W/$name.want"
    if [ -f "$(sib "$src" diag)" ]; then sed 's/^[[:space:]]*//;s/[[:space:]]*$//' "$(sib "$src" diag)" | grep -v '^$' | LC_ALL=C sort > "$want"; else : > "$want"; fi
    LC_ALL=C sort "$W/$name.out" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$' > "$W/$name.got" || true
    cmp -s "$want" "$W/$name.got" || { echo "diagnostics differ (expected < > got):"; diff "$want" "$W/$name.got" | head -10; head -3 "$W/$name.err"; return 1; }
}

t_memrun() { # <src>
    local src="$1" name; name="mr_$(basename "$src" .kt)"
    "$MINIC" -o "$W/$name.c" "$src" --stdlib $(cat "$W/stdlib.txt") > "$W/$name.log" 2>&1; local rc=$?
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
    "$MINIC" -o "$d/out.c" "$d/a.kt" "$d/b.kt" --stdlib $(cat "$W/stdlib.txt") > "$d/log" 2>&1 || { echo "build failed:"; head "$d/log"; return 1; }
    "$CC" -std=c17 -O1 -Wall $WNO -Werror -I "$INCLUDE" "$d/out.c" "$RTLIB" -o "$d/out" -lm > "$d/cc" 2>&1 || { echo "C compiler failed:"; head "$d/cc"; return 1; }
    [ "$("$d/out")" = "$(printf 'Hello, MINI!!\n2')" ] || { echo "wrong output"; return 1; }
    grep -q "ArrayList" "$d/out.c" && { echo "unreachable stdlib code was emitted"; return 1; }
    grep -q "kt_kotlin_Dtext_Duppercase_AS" "$d/out.c" || { echo "reachable stdlib code is missing"; return 1; }
    return 0
}

t_allfiles() { # the memory checker looks at every user file, not only the first
    local d="$W/allfiles"; mkdir -p "$d"
    printf '@file:MemoryMode(Mode.Strict)\nimport mini.memory.*\n\n@AllocatedBy(Heap::class)\nclass Box(val v: Int)\n\nfun main() {\n    val b = Box(1)\n    b.free()\n    leak()\n}\n' > "$d/a.kt"
    printf '@file:MemoryMode(Mode.Strict)\nimport mini.memory.*\n\nfun leak() {\n    val c = Box(2)\n    println(c.v)\n}\n' > "$d/b.kt"
    "$MINIC" --check "$d/a.kt" "$d/b.kt" --stdlib $(cat "$W/stdlib.txt") > "$d/chk" 2>&1; local rc=$?
    [ $rc -eq 1 ] || { echo "the leak in the second file must be reported (exit $rc)"; head "$d/chk"; return 1; }
    grep -q "M0102" "$d/chk" && grep -q "b.kt:" "$d/chk" || { echo "diagnostic of b.kt expected"; head "$d/chk"; return 1; }
    "$MINIC" -o "$d/prog.c" "$d/a.kt" "$d/b.kt" --stdlib $(cat "$W/stdlib.txt") > "$d/bld" 2>&1; rc=$?
    [ $rc -eq 1 ] && grep -q "M0102" "$d/bld" || { echo "build must fail on the memory error in b.kt (exit $rc)"; head "$d/bld"; return 1; }
    # the checker rejects and accepts
    "$MINIC" --check "$TESTDATA/memory/uaf_simple.kt" --stdlib $(cat "$W/stdlib.txt") > /dev/null 2>&1 && { echo "uaf_simple.kt must be rejected"; return 1; }
    "$MINIC" --check "$TESTDATA/memory/ok_heap_basic.kt" --stdlib $(cat "$W/stdlib.txt") > /dev/null 2>&1 || { echo "ok_heap_basic.kt must be accepted"; return 1; }
    return 0
}

# ---- driver -----------------------------------------------------------------------------------------------------------------------

export WNO ROOT NATIVE MINIC RTLIB INCLUDE TESTDATA CC SANCC ASAN_LEAKS SAN_FLAGS W NATIVE_DRIVER MINIC_API
export -f sib has_lines_in links_of c_extra run_and_compare compile_to_c t_c t_llvm t_llvm_variant t_llvm_api t_memcheck t_memrun t_multifile t_allfiles

# a test list: "<suite> <id> <function and arguments>" per line
LISTFILE="$W/tests.txt"
: > "$LISTFILE"
add() { echo "$1|$2|$3" >> "$LISTFILE"; }
progs() { find "$TESTDATA" -maxdepth 1 -name '*.kt' | LC_ALL=C sort; }
leakable() { # sources with .out and no .err/.cerr/.noleakcheck
    local d f
    for d in "$TESTDATA" "$TESTDATA/memory"; do
        [ -d "$d" ] || continue
        for f in $(find "$d" -maxdepth 1 -name '*.kt' | LC_ALL=C sort); do
            [ -f "${f%.kt}.out" ] && [ ! -f "${f%.kt}.cerr" ] && [ ! -f "${f%.kt}.err" ] && [ ! -f "${f%.kt}.noleakcheck" ] && echo "$f"
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
want_suite multifile && { add multifile twoFilesAndTheStdlib t_multifile; add multifile allUserFilesAreChecked t_allfiles; }

if [ -n "$FILTER" ]; then grep -F -- "$FILTER" "$LISTFILE" > "$LISTFILE.f" || true; mv "$LISTFILE.f" "$LISTFILE"; fi
total="$(wc -l < "$LISTFILE" | tr -d ' ')"
if [ "$LIST" = 1 ]; then awk -F'|' '{ print $1 "::" $2 }' "$LISTFILE"; echo "$total tests" >&2; exit 0; fi

# runtime objects for the sanitizer / stats flavours, once
if grep -qE '^(leak|llvm-leak)\|' "$LISTFILE"; then
    if [ -n "$SANCC" ]; then build_rt "$W/rt-asan" "$SANCC" $SAN_FLAGS; else echo "test-e2e: no C compiler can link the sanitizer runtimes: the leak suites are skipped" >&2; fi
fi
if grep -q '^llvm-variants|' "$LISTFILE"; then build_rt "$W/rt-stats" "$CC" -DMK_STATS; fi

run_one() { # "<suite>|<id>|<cmd>"
    local suite id cmd out rc
    suite="${1%%|*}"; local rest="${1#*|}"; id="${rest%%|*}"; cmd="${rest#*|}"
    case "$suite" in leak|llvm-leak) [ -n "$SANCC" ] || { echo "$suite|$id|SKIP|no sanitizer runtime" > "$W/res/$(echo "$suite-$id" | tr '/ ' '__')"; return; } ;; esac
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
echo
for s in "${!bysuite[@]}"; do echo "  $s: ${bysuite[$s]}"; done | sort
echo "test result: $([ $fail -eq 0 ] && echo ok || echo FAILED). $pass passed; $fail failed; $skip skipped"
[ $fail -eq 0 ]
