#!/usr/bin/env bash
# Runs the libFuzzer harnesses built by build.sh.
#
#   tools/fuzz/libfuzzer/run.sh [--out DIR] [--time SECONDS] [--regress] [--jobs N] [harness...]
#
#   (default)   fuzz every harness for --time seconds (default 300) from the seeds in seeds/NAME and the regression inputs in regress/NAME;
#               the grown corpus is kept in DIR/corpus/NAME, a crash is left as DIR/crash-* (reproduce: DIR/fuzz_NAME DIR/crash-..., minimise it
#               with `DIR/fuzz_NAME -minimize_crash=1 -runs=100000 crash-...`, then store it in regress/NAME/)
#   --regress   replay only the stored regression inputs (regress/NAME/*), once each: fast, deterministic, for CI
# Exit status 1 when a harness crashed.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${TMPDIR:-/tmp}/mini-libfuzzer" TIME=300 REGRESS=0 JOBS=1
WANT=()
while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT="$2"; shift 2 ;;
        --time) TIME="$2"; shift 2 ;;
        --regress) REGRESS=1; shift ;;
        --jobs) JOBS="$2"; shift 2 ;;
        *) WANT+=("$1"); shift ;;
    esac
done
[ ${#WANT[@]} -gt 0 ] || WANT=(utf8 numfmt parse numstr format regex text)
rc=0
one() {
    local h="$1" bin="$OUT/fuzz_$1" log="$OUT/$1.log"
    [ -x "$bin" ] || { echo "run.sh: $bin missing (build.sh)"; return 1; }
    local seeds=() d
    for d in "$HERE/regress/$h" "$HERE/seeds/$h"; do [ -d "$d" ] && seeds+=("$d"); done
    if [ "$REGRESS" = 1 ]; then
        [ -d "$HERE/regress/$h" ] || { echo "$h: no regression inputs"; return 0; }
        (cd "$OUT" && "$bin" -runs=1 "$HERE/regress/$h"/* > "$log" 2>&1) || { echo "$h: REGRESSION FAILED (see $log)"; tail -20 "$log"; return 1; }
        echo "$h: regression inputs ok"; return 0
    fi
    mkdir -p "$OUT/corpus/$h"
    (cd "$OUT" && ASAN_OPTIONS="detect_leaks=1:abort_on_error=0" UBSAN_OPTIONS="print_stacktrace=1" "$bin" -max_total_time="$TIME" -max_len=256 -timeout=30 \
        -rss_limit_mb=2048 -print_final_stats=1 -artifact_prefix="$OUT/crash-$h-" "$OUT/corpus/$h" "${seeds[@]}" > "$log" 2>&1)
    local st=$?
    grep -E "^(Done|stat::(number_of_executed_units|average_exec_per_sec)|stat::slowest)" "$log" | tr '\n' ' '; echo
    if [ $st -ne 0 ]; then echo "$h: CRASH (status $st, see $log)"; grep -E "ERROR|SUMMARY|runtime error|Uncaught" "$log" | head -5; return 1; fi
    echo "$h: ok"
}
export -f one; export OUT TIME REGRESS HERE
printf '%s\n' "${WANT[@]}" | xargs -P "$JOBS" -I{} bash -c 'one {}' || rc=1
exit $rc
