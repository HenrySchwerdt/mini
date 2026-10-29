#!/usr/bin/env bash
# Builds / checks every sample under website/samples with ./minic and compares the result with the
# expectation next to it. The docs include these files verbatim, so a passing run means every code
# listing on the site is real, compiling Mini.
#
# Expectation files (next to NAME.kt):
#   NAME.out    the program is built and run; its stdout must equal this file
#   NAME.check  the program is only passed through `minic check` (Strict-mode checker). Content:
#                 ok               accepted, no diagnostics, exit 0
#                 error M0102      rejected with exactly this code (exit 1)
#                 warning M0202    accepted (exit 0) but this warning is reported
#
# Usage: website/scripts/check-samples.sh [substring-filter]
# Requires: JDK 21, clang. `./minic` builds the compiler on first use.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
samples="$root/website/samples"
filter="${1:-}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cd "$root"
# Build the compiler once up front so the per-sample timings are not skewed and errors show early.
./minic check examples/hello.kt >/dev/null 2>"$tmp/warmup.log" || {
    echo "minic is not usable:"; grep -v JAVA_TOOL_OPTIONS "$tmp/warmup.log"; exit 2; }

pass=0
fail=0
failed=()

report_fail() {
    echo "FAIL  $1: $2"
    fail=$((fail + 1))
    failed+=("$1")
}

while IFS= read -r kt; do
    rel="${kt#"$samples"/}"
    [ -n "$filter" ] && [[ "$rel" != *"$filter"* ]] && continue
    base="${kt%.kt}"
    name="${rel%.kt}"
    if [ -f "$base.out" ]; then
        exe="$tmp/$(echo "$name" | tr '/' '_')"
        if ! ./minic build "$kt" -o "$exe" >"$tmp/build.log" 2>&1; then
            report_fail "$name" "build failed"
            grep -v JAVA_TOOL_OPTIONS "$tmp/build.log" | head -5 | sed 's/^/        /'
            continue
        fi
        "$exe" >"$tmp/actual.out" 2>"$tmp/actual.err"
        status=$?
        if [ $status -ne 0 ]; then
            report_fail "$name" "exit status $status"
            head -3 "$tmp/actual.err" | sed 's/^/        /'
        elif ! diff -u "$base.out" "$tmp/actual.out" >"$tmp/diff.txt"; then
            report_fail "$name" "output differs from $name.out"
            head -20 "$tmp/diff.txt" | sed 's/^/        /'
        else
            echo "ok    $name (run)"
            pass=$((pass + 1))
        fi
    elif [ -f "$base.check" ]; then
        read -r kind code <"$base.check"
        ./minic check --brief "$kt" >"$tmp/brief.txt" 2>"$tmp/check.err"
        status=$?
        codes="$(sed -n 's/^[0-9]*:[0-9]*: \(M[0-9]*\).*/\1/p' "$tmp/brief.txt" | sort -u | tr '\n' ' ')"
        case "$kind" in
            ok)
                if [ $status -eq 0 ] && [ -z "$codes" ]; then
                    echo "ok    $name (check: accepted)"; pass=$((pass + 1))
                else
                    report_fail "$name" "expected no diagnostics, got status $status, codes: $codes"
                fi ;;
            error)
                if [ $status -eq 1 ] && [ "$codes" = "$code " ]; then
                    echo "ok    $name (check: $code)"; pass=$((pass + 1))
                else
                    report_fail "$name" "expected exactly error $code, got status $status, codes: $codes"
                fi ;;
            warning)
                if [ $status -eq 0 ] && [ "$codes" = "$code " ]; then
                    echo "ok    $name (check: warning $code)"; pass=$((pass + 1))
                else
                    report_fail "$name" "expected exactly warning $code and exit 0, got status $status, codes: $codes"
                fi ;;
            *) report_fail "$name" "unknown expectation '$kind' in $name.check" ;;
        esac
    else
        report_fail "$name" "no $name.out or $name.check next to the sample"
    fi
done < <(find "$samples" -name '*.kt' | sort)

echo
echo "samples: $pass passed, $fail failed"
if [ $fail -ne 0 ]; then
    printf 'failed: %s\n' "${failed[@]}"
    exit 1
fi
