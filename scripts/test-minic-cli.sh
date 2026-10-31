#!/bin/sh
# Tests the command line of a release's bin/minic (scripts/minic.sh): help, version, diagnostics, LLVM API defaults and
# the explicit C fallback.
#
#   scripts/test-minic-cli.sh <MINI_HOME>      the unpacked release (bin/minic, bin/minic-native, lib/, stdlib/, ...)
set -u
HOME_DIR="${1:?usage: test-minic-cli.sh <unpacked release directory>}"
HOME_DIR="$(cd "$HOME_DIR" && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
M="$HOME_DIR/bin/minic"
W="$(mktemp -d "${TMPDIR:-/tmp}/minic-cli-test.XXXXXX")"
trap 'rm -rf "$W"' EXIT
cd "$W" || exit 1
fails=0
pass() { printf 'ok   %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; fails=$((fails + 1)); }
# expect <name> <expected status> <expected substring of stdout+stderr> <command...>
expect() {
    name="$1"; want="$2"; sub="$3"; shift 3
    out="$("$@" 2>&1)"; st=$?
    if [ "$st" != "$want" ]; then fail "$name (exit $st, wanted $want): $out"; return; fi
    case "$out" in *"$sub"*) pass "$name" ;; *) fail "$name (missing '$sub'): $out" ;; esac
}
expect_absent() { # <name> <substring that must not appear> <command...>
    name="$1"; sub="$2"; shift 2
    out="$("$@" 2>&1)"
    case "$out" in *"$sub"*) fail "$name (found '$sub'): $out" ;; *) pass "$name" ;; esac
}

cp "$ROOT/examples/hello.kt" "$ROOT/examples/memory_leak.kt" .
cat > bad.kt <<'EOF'
fun main() {
    val x: Int = "hello"
    println(y)
}
EOF
cat > args.kt <<'EOF'
fun main(args: Array<String>) {
    println(args.size)
    for (a in args) println(a)
}
EOF
cat > many.kt <<'EOF'
fun main() {
    a1
    a2
    a3
}
EOF

expect "--version" 0 "minic " "$M" --version
expect "version command" 0 "minic " "$M" version
expect "--help" 0 "Usage: minic <command>" "$M" --help
expect "build --help" 0 "Usage: minic build" "$M" build --help
expect "run --help" 0 "Usage: minic run" "$M" run --help
expect "check --help" 0 "Usage: minic check" "$M" check --help
expect "help build" 0 "Usage: minic build" "$M" help build
expect "no arguments" 2 "Usage: minic <command>" "$M"
expect "unknown command" 2 "did you mean 'build'?" "$M" biuld hello.kt
expect "unknown option" 2 "did you mean '--error-format'?" "$M" build hello.kt --error-formt=json
expect "unknown option without a guess" 2 "unknown option '--zzzzzzzzzz'" "$M" build hello.kt --zzzzzzzzzz
expect "bare file name" 2 "minic run x.kt" "$M" x.kt
expect "missing file" 1 "file 'nope.kt' not found" "$M" build nope.kt
expect "file without extension" 1 "did you mean 'hello.kt'?" "$M" build hello
expect "no input" 2 "no input files" "$M" build

# diagnostics
expect "type error position" 1 "bad.kt:2:18: error: type mismatch" "$M" check bad.kt
expect "snippet and caret" 1 '2 |     val x: Int = "hello"' "$M" check bad.kt
expect "summary line" 1 "error: could not compile bad.kt (2 errors, 0 warnings)" "$M" check bad.kt
expect "memory diagnostic code" 1 "error[M0102]" "$M" check memory_leak.kt
expect "memory diagnostic docs link" 1 "https://henryschwerdt.github.io/mini/reference/diagnostics/M0102" "$M" check memory_leak.kt
expect "brief" 1 "14:15: M0102" "$M" check --brief memory_leak.kt
expect "json" 1 '"severity":"error","code":"M0102"' "$M" check --error-format=json memory_leak.kt
expect "json type error" 1 '"line":2,"col":18' "$M" check --error-format=json bad.kt
expect "error limit" 1 "too many errors: showing the first 2 of 3" "$M" check --error-limit=2 many.kt
expect_absent "no color without a terminal" "$(printf '\033')" "$M" check bad.kt
expect "color always" 1 "$(printf '\033[1;31m')" "$M" check --color=always bad.kt
expect_absent "color never" "$(printf '\033')" "$M" check --color=never bad.kt
expect_absent "NO_COLOR" "$(printf '\033')" env NO_COLOR=1 "$M" check --color=auto bad.kt
expect "check ok" 0 "" "$M" check hello.kt

# build and run
expect "build" 0 "" "$M" build hello.kt
if [ -x ./hello ] && [ "$(./hello | head -1)" = "Hello, Mini!" ]; then pass "default output name"; else fail "default output name"; fi
expect "build -o -O3" 0 "" "$M" build hello.kt -O3 -o hello3
expect "build --debug" 0 "" "$M" build --debug hello.kt -o hellog
expect "run" 0 "Hello, Mini!" "$M" run hello.kt
expect "run with args" 0 "two words" "$M" run args.kt -- one "two words"
expect "run type error" 1 "bad.kt:2:18: error" "$M" run bad.kt
expect "debug build keeps the Kotlin lines" 0 "" "$M" build -g hello.kt -o hellog2 --emit-c hellog2.c
if grep -q '^#line 3 "hello.kt"' hellog2.c; then pass "#line directives in --debug builds"; else fail "#line directives in --debug builds"; fi
expect "emit-c" 0 "" "$M" emit-c hello.kt -o hello.c
if grep -q 'int main\|mk_main' hello.c; then pass "emit-c writes C"; else fail "emit-c writes C"; fi
expect "LLVM API default" 0 "emit LLVM object and link" "$M" build hello.kt -o hellot --time
expect "C backend fallback" 0 "" "$M" build --backend=c hello.kt -o helloc
if [ -x ./helloc ] && [ "$(./helloc | head -1)" = "Hello, Mini!" ]; then pass "C backend executable"; else fail "C backend executable"; fi
expect "REPL does not require a C compiler" 0 "Mini native REPL" env CC=definitely-not-a-compiler "$HOME_DIR/bin/mini" repl
expect "debug help states API limitation" 0 "only the C fallback adds native debug symbols" "$M" build --help

# C compiler problems
expect "missing C compiler" 1 "was not found" env CC=definitely-not-a-compiler "$M" build hello.kt
cat > badcc <<'EOF'
#!/bin/sh
echo "generated.c:3:1: error: unknown type name 'foo'" >&2
exit 1
EOF
chmod +x badcc
expect "C emitter compiler error is an internal error" 3 "internal compiler error" "$M" build --backend=c hello.kt --cc "$W/badcc"
expect "internal error names the issue tracker" 3 "github.com/HenrySchwerdt/mini/issues" "$M" build --backend=c hello.kt --cc "$W/badcc"
rm -f minic-ice-hello.c
cat > badld <<'EOF2'
#!/bin/sh
case "$*" in *" -c "*) exec cc "$@" ;; *) echo "ld: cannot find -lfoo" >&2; exit 1 ;; esac
EOF2
chmod +x badld
expect "API link failure is not an internal error" 1 "linking" "$M" build hello.kt --cc "$W/badld"

cat > ccache <<'EOF3'
#!/bin/sh
printf '%s\n' "$*" >> "$CC_LOG"
exec "$@"
EOF3
chmod +x ccache
expect "API preserves compound CC command and flags" 0 "" env PATH="$W:$PATH" CC_LOG="$W/cc.log" CC="ccache clang -DMINIC_CC_FLAG=1" "$M" build hello.kt -o hellocc
if grep -q -- '-DMINIC_CC_FLAG=1' "$W/cc.log"; then pass "API forwarded CC flags"; else fail "API forwarded CC flags"; fi

if [ "$fails" -ne 0 ]; then echo "$fails failure(s)"; exit 1; fi
echo "all minic CLI tests passed"
