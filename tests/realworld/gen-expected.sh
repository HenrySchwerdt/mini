#!/usr/bin/env bash
# Writes the expected output (<name>.out) of the realworld programs with the reference compiler (kotlinc on the JVM).
#   tests/realworld/gen-expected.sh [name...]      (default: every program whose .out is missing)
#   KOTLINC=/path/to/kotlinc tests/realworld/gen-expected.sh --all
# The programs are compiled and run by the reference compiler only to get their output: they use the Kotlin standard library only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
all=0
names=()
for a in "$@"; do
    if [ "$a" = --all ]; then all=1; else names+=("$a"); fi
done
if [ ${#names[@]} -eq 0 ]; then
    for f in "$HERE"/*.kt; do
        n="$(basename "$f" .kt)"
        if [ "$all" = 1 ] || [ ! -f "$HERE/$n.out" ]; then names+=("$n"); fi
    done
fi
for n in "${names[@]}"; do
    echo "== $n" >&2
    "$KOTLINC" -nowarn "$HERE/$n.kt" -include-runtime -d "$W/$n.jar" 2> "$W/$n.err" || { cat "$W/$n.err" >&2; exit 1; }
    java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -jar "$W/$n.jar" > "$HERE/$n.out"
done
