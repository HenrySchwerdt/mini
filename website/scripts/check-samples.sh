#!/usr/bin/env bash
# Builds / checks every sample under website/samples with ./minic and compares the result with the
# expectation next to it. The docs include these files verbatim, so a passing run means every code
# listing on the site is real, compiling Mini.
#
# Expectation files (next to NAME.kt):
#   NAME.out    the program is built and run; its stdout must equal this file
#   NAME.run    the program is built and run; it must exit with the status written in the file (default 0); stdout is not compared
#   NAME.norun  (web only) the docs show the sample but offer no Run/Check in the browser; not checked here
#   NAME.check  the program is only passed through `minic check` (Strict-mode checker). Content:
#                 ok               accepted, no diagnostics, exit 0
#                 error M0102      rejected with exactly this code (exit 1)
#                 warning M0202    accepted (exit 0) but this warning is reported
#
# Runnable code blocks written inline in the markdown pages are checked the same way: scripts/extract-snippets.mjs writes the
# program each block runs in the browser (with the same .out / .run / .check files); they are listed as `page:<name>`.
#
# Usage: website/scripts/check-samples.sh [substring-filter]
# Rich output (docs/display.md): a program that prints display bundles is compared with the bundles replaced by `[display: <mime>]`
# lines (scripts/normalize-display.mjs, which also checks that an SVG is an SVG). Programs that use a library of libs/ (`import mini.plot.*`)
# are built together with its sources, like a path dependency.
# Requires: a C compiler and `npm ci` in website/ (snippet extraction). `./minic` builds the compiler on first use (native, no JVM).
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
samples="$root/website/samples"
filter="${1:-}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
snippets="$tmp/snippets"
node "$root/website/scripts/extract-snippets.mjs" "$snippets" || { echo "extract-snippets failed (run 'npm ci' in website/ first)"; exit 2; }

cd "$root"
# Build the compiler once up front so the per-sample timings are not skewed and errors show early.
./minic check examples/hello.kt >/dev/null 2>"$tmp/warmup.log" || {
    echo "minic is not usable:"; cat "$tmp/warmup.log"; exit 2; }

pass=0
fail=0
failed=()

# lib_sources <program.kt>: the sources of every libs/<name>/src whose package the program mentions
lib_sources() {
    local lib pkg
    for lib in "$root"/libs/*/; do
        [ -d "$lib/src" ] || continue
        while IFS= read -r pkg; do
            if grep -qE "(^|[^.[:alnum:]_])${pkg//./\\.}([^[:alnum:]_]|$)" "$1"; then
                find "$lib/src" -name '*.kt' | LC_ALL=C sort
                return
            fi
        done < <(grep -rh '^package ' "$lib/src" | sed 's/^package //' | sort -u)
    done
}

report_fail() {
    echo "FAIL  $1: $2"
    fail=$((fail + 1))
    failed+=("$1")
}

while IFS= read -r kt; do
    rel="${kt#"$samples"/}"
    case "$kt" in "$snippets"/*) rel="page:${kt#"$snippets"/}" ;; esac
    [ -n "$filter" ] && [[ "$rel" != *"$filter"* ]] && continue
    base="${kt%.kt}"
    name="${rel%.kt}"
    if [ -f "$base.out" ] || [ -f "$base.run" ]; then
        exe="$tmp/$(echo "$name" | tr '/:' '__')"
        # shellcheck disable=SC2046
        if ! ./minic build "$kt" $(lib_sources "$kt") -o "$exe" >"$tmp/build.log" 2>&1; then
            report_fail "$name" "build failed"
            head -5 "$tmp/build.log" | sed 's/^/        /'
            continue
        fi
        "$exe" >"$tmp/raw.out" 2>"$tmp/actual.err"
        status=$?
        node "$root/website/scripts/normalize-display.mjs" <"$tmp/raw.out" >"$tmp/actual.out" 2>>"$tmp/actual.err" || status=97
        want_status=0
        [ -f "$base.run" ] && want_status="$(tr -d ' \n' <"$base.run")"
        [ -n "$want_status" ] || want_status=0
        if [ "$status" -ne "$want_status" ]; then
            report_fail "$name" "exit status $status"
            head -3 "$tmp/actual.err" | sed 's/^/        /'
        elif [ -f "$base.out" ] && ! diff -u "$base.out" "$tmp/actual.out" >"$tmp/diff.txt"; then
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
        [ -f "$base.norun" ] && continue
        report_fail "$name" "no $name.out, $name.run or $name.check next to the sample"
    fi
done < <(find "$samples" "$snippets" -name '*.kt' | sort)

echo
echo "samples: $pass passed, $fail failed"
if [ $fail -ne 0 ]; then
    printf 'failed: %s\n' "${failed[@]}"
    exit 1
fi
