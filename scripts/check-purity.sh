#!/usr/bin/env bash
# Self-hosting rule (docs/plan-kotlin.md 10a): the compiler and the `mini` tool are pure Kotlin, so Mini can compile them itself.
# Their sources must not use java.*, javax.*, kotlin.jvm.*, @Jvm* annotations, JVM reflection or System.*, and must not contain
# Java sources; the packages may depend only on the packages listed in DEPS below (read from each mini.toml).
# (The native build would fail on such code anyway; this check gives the clear message and guards the dependency rule.)
#
#   scripts/check-purity.sh          exit 0 = clean
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/compiler"

PATTERN='\bjava\.|\bjavax\.|\bkotlin\.jvm\b|\bkotlin\.reflect\b|\bkotlin\.system\b|@Jvm[A-Z]|::class\.java\b|\.javaClass\b|\bClass\.forName\b|\bSystem\.|\bProcessBuilder\b|\bkotlin\.io\.path\b'

# the code of a source line: no comment lines, no string literals, no // tail
code_lines() {
    awk '{
        t = $0; sub(/^[ \t]+/, "", t)
        if (t ~ /^(\*|\/\*|\/\/)/) next
        gsub(/"([^"\\]|\\.)*"/, "\"\"")
        sub(/\/\/.*$/, "")
        print FNR ": " $0
    }' "$1"
}

violations() { # <file>
    code_lines "$1" | grep -E "$PATTERN" | sed "s|^|$1:|"
}

# declared package dependencies: package -> allowed names
declare -A DEPS=(
    [backend]=""
    [frontend]=""
    [lower]="frontend backend"
    [minic-native]="lower frontend backend"
    [native-driver]="backend"
    [mini-tool]="frontend"
)

fail=0

# self-test: the checker must see violations
selftest() {
    local tmp; tmp="$(mktemp)"
    local bad=("import java.io.File" "import kotlin.jvm.JvmStatic" "@JvmStatic fun f() {}" "val c = Foo::class.java" "System.currentTimeMillis()" "val p = ProcessBuilder()")
    for l in "${bad[@]}"; do
        echo "$l" > "$tmp"
        [ -n "$(violations "$tmp")" ] || { echo "check-purity: self-test failed, not detected: $l" >&2; fail=1; }
    done
    for l in "val x = 1 + 2" "val x = 1 // java.io in a comment" "val s = \"java.io\"" " * java.io.File in KDoc"; do
        echo "$l" > "$tmp"
        [ -z "$(violations "$tmp")" ] || { echo "check-purity: self-test failed, false positive: $l" >&2; fail=1; }
    done
    rm -f "$tmp"
}
selftest

count=0
for pkg in "${!DEPS[@]}"; do
    [ -d "$pkg/src" ] || { echo "check-purity: $pkg/src missing" >&2; fail=1; continue; }
    if [ -n "$(find "$pkg/src" -name '*.java' -print -quit)" ]; then echo "check-purity: $pkg must not contain Java sources" >&2; fail=1; fi
    while IFS= read -r f; do
        count=$((count + 1))
        v="$(violations "$f")"
        if [ -n "$v" ]; then echo "$v" >&2; fail=1; fi
    done < <(find "$pkg/src" -name '*.kt' | LC_ALL=C sort)
    # dependencies: the names in [dependencies] of mini.toml
    declared="$(awk '/^\[dependencies\]/ { d = 1; next } /^\[/ { d = 0 } d && /=/ { sub(/[ \t]*=.*/, ""); print }' "$pkg/mini.toml" | sort | tr '\n' ' ')"
    allowed="$(printf '%s\n' ${DEPS[$pkg]} | sort | tr '\n' ' ')"
    if [ "$declared" != "$allowed" ] && ! { [ -z "${DEPS[$pkg]}" ] && [ -z "${declared// }" ]; }; then
        echo "check-purity: $pkg/mini.toml declares dependencies [${declared% }], allowed [${allowed% }]" >&2
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then echo "check-purity: FAILED" >&2; exit 1; fi
echo "check-purity: ok ($count source files in ${#DEPS[@]} packages)"
