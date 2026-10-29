#!/usr/bin/env bash
# Writes the bootstrap seed mini-<version>-bootstrap-c.tar.gz: the generated C of the compiler plus the runtime
# sources and a build.sh, so that a C compiler alone builds a working native stage0 (docs/plan-kotlin.md 10f).
#
#   scripts/package-seed.sh <version> <compiler.c> <out-dir>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:?usage: package-seed.sh <version> <compiler.c> <out-dir>}"
CFILE="${2:?missing compiler.c}"
OUT="$(mkdir -p "${3:?missing out-dir}" && cd "$3" && pwd)"
NAME="mini-$VERSION-bootstrap-c"
STAGE="$(mktemp -d)/$NAME"
mkdir -p "$STAGE/runtime"
cp "$CFILE" "$STAGE/minic.c"
cp -R "$ROOT/runtime/include" "$ROOT/runtime/src" "$STAGE/runtime/"
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$STAGE/"
echo "$VERSION" > "$STAGE/VERSION"

cat > "$STAGE/build.sh" <<'EOF'
#!/bin/sh
# Builds the native stage0 compiler from the seed with a C compiler alone: ./minic-native
#   CC=clang OPT=-O1 ./build.sh [output]
set -e
cd "$(dirname "$0")"
${CC:-cc} -std=c17 ${OPT:--O1} -w -I runtime/include minic.c runtime/src/*.c -o "${1:-minic-native}" -lm
echo "built ${1:-minic-native}"
EOF
chmod +x "$STAGE/build.sh"

cat > "$STAGE/README.md" <<EOF
# Mini $VERSION bootstrap seed

\`minic.c\` is the C the self-hosted Mini compiler generates for its own sources (stage 2 of compiler/bootstrap.sh).
With a C compiler this gives a working compiler, no JDK and no prebuilt binary needed:

    ./build.sh                                   # -> ./minic-native (stage0)
    # in a checkout of the same version of https://github.com/HenrySchwerdt/mini:
    compiler/bootstrap.sh --stage0 /path/to/minic-native     # stage1 -> stage2 -> stage3, stage2 == stage3

\`minic-native [-o out.c] [--emit-mir out.mir] <user.kt>... --stdlib <files>...\` (Kotlin to C).
EOF

tar -C "$(dirname "$STAGE")" -czf "$OUT/$NAME.tar.gz" "$NAME"
echo "wrote $OUT/$NAME.tar.gz"
