#!/usr/bin/env bash
# CI: build the native `mini` project tool with the compiler of a bootstrap work dir (no JVM) and smoke-test it:
# mini new, build, run, test, check, and a broken program must fail with a diagnostic.
#
#   scripts/ci-mini-smoke.sh <work-dir>     the work dir ci-stage0.sh was given (uses s0/stage3)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:?usage: ci-mini-smoke.sh <work-dir>}"

minic=""
for cand in "$WORK/s0/stage3"; do
    [ -x "$cand" ] && { minic="$cand"; break; }
done
[ -n "$minic" ] || { echo "ci-mini-smoke.sh: no stage3 compiler below $WORK" >&2; exit 1; }

"$ROOT/scripts/build-mini.sh" --minic-native "$minic" --out "$WORK/native"
mini="$WORK/native/bin/mini"
file "$mini" 2>/dev/null | head -n 1 || true

d="$WORK/smoke"
rm -rf "$d"; mkdir -p "$d"; cd "$d"
"$mini" --version
"$mini" new demo
cd demo
"$mini" build
[ "$("$mini" run --quiet)" = "Hello, world!" ] || { echo "mini run printed something else" >&2; exit 1; }
"$mini" test
"$mini" check

# an error is reported with its position and fails the build
cp src/main.kt src/main.kt.ok
printf 'fun main() {\n    val x: Int = "text"\n    println(x)\n}\n' > src/main.kt
if "$mini" build 2> err.txt; then echo "a broken program built" >&2; exit 1; fi
cat err.txt
grep -q 'error' err.txt
mv src/main.kt.ok src/main.kt
"$mini" build
echo "mini smoke test OK"
