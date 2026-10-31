#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
MINIC_API="${MINIC_API:-$ROOT/.build/repl-final/stage3}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mini-repl.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/input" <<'INPUT'
val answer = 40
fun nextAnswer() = answer + 2
nextAnswer()
var mutations = 1
:do mutations += 1
mutations
var initRuns = 0
fun initializer(): Int { initRuns += 1; println("initialized once"); return 7 }
val initialized = initializer()
initialized
initialized
initRuns
val broken: Int = "not an Int"
nextAnswer()
val answer = 8
nextAnswer()
:quit
INPUT

STDLIB="$(find "$ROOT/stdlib" -name '*.kt' | LC_ALL=C sort | tr '\n' ' ')"
# shellcheck disable=SC2086
"$MINIC_API" --backend=llvm-api --repl --stdlib $STDLIB < "$WORK/input" > "$WORK/out" 2> "$WORK/err"

cat > "$WORK/expected" <<'EXPECTED'
Mini native REPL. Enter `val`/`var`/`fun` declarations or expressions; `:do <statement>`, `:help`, `:quit`.
42
2
initialized once
7
7
1
42
42
EXPECTED
diff -u "$WORK/expected" "$WORK/out"
grep -q 'could not compile ReplCell12.kt' "$WORK/err"
grep -q "redefinition of 'answer'" "$WORK/err"

cat > "$WORK/args.kt" <<'SOURCE'
fun main(args: Array<String>) { println(args.size) }
SOURCE
# shellcheck disable=SC2086
"$MINIC_API" "$WORK/args.kt" --backend=llvm-api --run --stdlib $STDLIB -- first second > "$WORK/args.out" 2> "$WORK/args.err"
grep -qx '2' "$WORK/args.out"

cat > "$WORK/throws.kt" <<'SOURCE'
fun main() { throw IllegalStateException("ORC exception smoke") }
SOURCE
# An uncaught exception follows the established one-shot process-failure path.
if "$MINIC_API" "$WORK/throws.kt" --backend=llvm-api --run --stdlib $STDLIB > "$WORK/throws.out" 2> "$WORK/throws.err"; then
    echo "expected the --run exception case to fail" >&2
    exit 1
fi
grep -q 'ORC exception smoke' "$WORK/throws.err"
echo "REPL session regression OK"
