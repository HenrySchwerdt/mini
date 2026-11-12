#!/usr/bin/env bash
# Writes <project>/expected.out with the reference compiler (kotlinc on the JVM): the sources of all packages of the workspace
# (src/ of every member, tests are not compiled) are compiled together; the app is the member that has a `fun main`.
#   tests/projects/gen-expected.sh [project...]      (default: every project without expected.out)
#   KOTLINC=/path/to/kotlinc tests/projects/gen-expected.sh --all
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
    for d in "$HERE"/*/; do
        n="$(basename "$d")"
        if [ "$all" = 1 ] || [ ! -f "$HERE/$n/expected.out" ]; then names+=("$n"); fi
    done
fi
for n in "${names[@]}"; do
    echo "== $n" >&2
    srcs=$(find "$HERE/$n" -path '*/src/*' -name '*.kt' | LC_ALL=C sort)
    # shellcheck disable=SC2086
    "$KOTLINC" -nowarn $srcs -include-runtime -d "$W/$n.jar" 2> "$W/$n.err" || { cat "$W/$n.err" >&2; exit 1; }
    mainkt="$(grep -l '^fun main' $srcs | head -1)"
    cls="$(basename "$mainkt" .kt)"; cls="$(echo "${cls:0:1}" | tr a-z A-Z)${cls:1}Kt"
    java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "$W/$n.jar" "$cls" > "$HERE/$n/expected.out"
done
