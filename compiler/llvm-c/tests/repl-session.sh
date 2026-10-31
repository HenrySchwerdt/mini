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
var held: String = ""
fun keepOwned(@kotlin.internal.TakesOwnership value: String) { held = value }
fun makeAndKeep() { val owner = "kept " + "alive"; val borrowed = owner; keepOwned(borrowed) }
:do makeAndKeep()
held
val strings = ArrayList<String>()
fun appendNew() { val owner = "list " + "value"; val borrowed = owner; strings.add(borrowed) }
:do appendNew()
strings.toString()
strings[0]
strings[0]
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
kept alive
[list value]
list value
list value
42
42
EXPECTED
diff -u "$WORK/expected" "$WORK/out"
grep -Eq 'could not compile ReplCell[0-9]+\.kt' "$WORK/err"
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

# An overlong input is rejected as a whole and drained through its newline; neither the prefix nor the tail can execute.
{
    printf ':do println("oversize prefix");'
    awk 'BEGIN { for (i = 0; i < 65537; i++) printf " " }'
    printf ':do println("oversize suffix")\n1\n:quit\n'
} > "$WORK/long-input"
# shellcheck disable=SC2086
"$MINIC_API" --backend=llvm-api --repl --stdlib $STDLIB < "$WORK/long-input" > "$WORK/long.out" 2> "$WORK/long.err"
printf 'Mini native REPL. Enter `val`/`var`/`fun` declarations or expressions; `:do <statement>`, `:help`, `:quit`.\n1\n' > "$WORK/long.expected"
diff -u "$WORK/long.expected" "$WORK/long.out"
grep -q 'REPL input exceeds 65536 bytes; input discarded' "$WORK/long.err"

if "$MINIC_API" "$WORK/args.kt" --backend=llvm-api --repl --stdlib $STDLIB > "$WORK/positional.out" 2> "$WORK/positional.err"; then
    echo "expected --repl to reject positional source files" >&2
    exit 1
fi
grep -q -- '--repl does not accept source files' "$WORK/positional.err"
echo "REPL session regression OK"
