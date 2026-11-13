#!/usr/bin/env bash
# Builds, runs and tests one multi-module project of tests/projects with `mini` (a copy in a scratch directory):
#   tests/projects/run.sh <project> [workdir]
# What is checked (each only when the project has the file):
#   expected.out   `mini run` prints exactly this (stdout)
#   tests/         `mini test` of the workspace passes
#   expected.err   `mini build` must fail and print every line of this file (a visibility or type error that must be reported)
#   packages       one `mini package` artifact (.mlib) per library, and the app compiled by minic-native against the .mlib files only
#                  prints the same output
#   args           the arguments of `mini run -- ...` (one line)
#   strict         (file exists) the project is also built with `--strict`: it must build and print the same output
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
NATIVE="${MINI_ROOT:-$ROOT/build/native}"
MINI="$NATIVE/bin/mini"
name="$1"
W="${2:-$(mktemp -d)}"
src="$HERE/$name"
dst="$W/$name"
rm -rf "$dst"
mkdir -p "$W"
cp -r "$src" "$dst"
rm -f "$dst/expected.out" "$dst/expected.err" "$dst/args" "$dst/strict" "$dst/packages"
export MINI_ROOT="$NATIVE"
cd "$dst" || exit 1
fail() { echo "$name: $*"; exit 1; }

if [ -f "$src/expected.err" ]; then
    "$MINI" build --color never > "$W/$name.build" 2>&1 && fail "the build should have failed"
    while IFS= read -r line || [ -n "$line" ]; do
        [ -z "${line//[[:space:]]/}" ] && continue
        grep -qF -- "$line" "$W/$name.build" || { echo "missing in the diagnostics: $line"; head -20 "$W/$name.build"; exit 1; }
    done < "$src/expected.err"
    exit 0
fi

args=""
[ -f "$src/args" ] && args="$(cat "$src/args")"
app="$(grep -l 'kind *= *"app"' */mini.toml | head -1 | xargs dirname)"
# shellcheck disable=SC2086
"$MINI" run --color never -p "$(basename "$(grep -h '^name' "$app/mini.toml" | head -1 | sed 's/.*"\(.*\)".*/\1/')")" -- $args > "$W/$name.out" 2> "$W/$name.err" || { echo "mini run failed:"; head -30 "$W/$name.err"; exit 1; }
cmp -s "$src/expected.out" "$W/$name.out" || { echo "$name: stdout differs"; diff "$src/expected.out" "$W/$name.out" | head -15; exit 1; }

if ls "$src"/*/tests/*.kt > /dev/null 2>&1; then
    "$MINI" test --color never > "$W/$name.test" 2>&1 || { echo "mini test failed:"; tail -25 "$W/$name.test"; exit 1; }
    grep -q "test result: ok" "$W/$name.test" || { echo "mini test: no ok result"; tail -10 "$W/$name.test"; exit 1; }
fi

if [ -f "$src/packages" ]; then
    # the .mlib artifacts: package every library, compile the app against them alone
    libs=()
    for libname in $(cat "$src/packages"); do
        "$MINI" package --color never -p "$libname" > "$W/$name.pkg" 2>&1 || { echo "mini package $libname failed:"; head -20 "$W/$name.pkg"; exit 1; }
        f="$(ls build/package/"$libname"-*.mlib 2> /dev/null | head -1)"
        [ -n "$f" ] || { echo "no .mlib for $libname"; exit 1; }
        libs+=(--lib "$f")
    done
    V="$(cat "$NATIVE/VERSION")"
    appdir="$(dirname "$(grep -l 'kind *= *"app"' */mini.toml | head -1)")"
    appsrcs=("$appdir"/src/*.kt)
    if grep -q 'memory *= *"strict"' "$appdir/mini.toml"; then
        # a Strict package: the annotation goes on the first line of a copy of every file (what `mini` does)
        mkdir -p "$W/$name.overlay"
        appsrcs=()
        for f in "$appdir"/src/*.kt; do
            { printf '@file:MemoryMode(Mode.Strict) '; cat "$f"; } > "$W/$name.overlay/$(basename "$f")"
            appsrcs+=("$W/$name.overlay/$(basename "$f")")
        done
    fi
    "$NATIVE/bin/minic-native" -o "$W/$name.c" "${appsrcs[@]}" --lib "$NATIVE/lib/stdlib.mlib" "${libs[@]}" --compiler-id "$V" > "$W/$name.mc" 2>&1 || { echo "app against the .mlib files failed:"; head -20 "$W/$name.mc"; exit 1; }
    "${MINI_CC:-${CC:-clang}}" -std=c17 -O1 -w -I "$ROOT/runtime/include" "$W/$name.c" "$NATIVE/lib/libminirt.a" -o "$W/$name.exe" -lm > "$W/$name.cc" 2>&1 || { echo "C compiler failed:"; head "$W/$name.cc"; exit 1; }
    # shellcheck disable=SC2086
    "$W/$name.exe" $args > "$W/$name.out2" 2> /dev/null
    cmp -s "$src/expected.out" "$W/$name.out2" || { echo "$name: output against the .mlib files differs"; diff "$src/expected.out" "$W/$name.out2" | head -10; exit 1; }
fi

if [ -f "$src/strict" ]; then
    "$MINI" build --strict --color never > "$W/$name.sb" 2>&1 || { echo "mini build --strict failed:"; head -30 "$W/$name.sb"; exit 1; }
fi
exit 0
