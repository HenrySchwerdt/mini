#!/usr/bin/env bash
# Builds the release tarball of one platform and (with --seed) the platform-independent bootstrap seed.
#
#   scripts/package-release.sh <version> <target> [--out <dir>] [--work <bootstrap-work-dir>] [--seed]
#
#   version   e.g. 0.4.0                         target  linux-x64 | linux-arm64 | macos-arm64 (any name)
#   --work    a work dir of compiler/bootstrap.sh holding stage3.c; without it the bootstrap runs first
#             (from a native stage0: scripts/ci-stage0.sh) and checks stage2 == stage3.
#   --out     where the archives go (default dist/)
#   --seed    also write mini-<version>-bootstrap-c.tar.gz (see scripts/package-seed.sh)
#
# Output: <out>/mini-<version>-<target>.tar.gz containing
#   bin/minic          shell driver (scripts/minic.sh): build, run, check, emit-c (compiler, then cc with the bundled runtime)
#   bin/minic-native   the self-hosted native compiler (the C of stage3, built with -O2)
#   bin/mini           the project tool (new, build, run, test, check, ...): Kotlin compiled by bin/minic-native, no JVM
#   lib/libminirt.a    the runtime, prebuilt;  include/minirt.h
#   stdlib/            the standard library sources (compiled together with every program)
#   LICENSE NOTICE README.md
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:?usage: package-release.sh <version> <target> [--out dir] [--work dir] [--seed]}"
TARGET="${2:?missing target}"
shift 2
OUT="$ROOT/dist"
WORK=""
SEED=0
while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT="$(mkdir -p "$2" && cd "$2" && pwd)"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --seed) SEED=1; shift ;;
        *) echo "package-release.sh: unknown argument $1" >&2; exit 2 ;;
    esac
done
CC="${CC:-clang}"
mkdir -p "$OUT"

if [ -z "$WORK" ]; then
    WORK="$(mktemp -d)"
    "$ROOT/compiler/bootstrap.sh" "$WORK"
fi
[ -f "$WORK/stage3.c" ] || { echo "package-release.sh: $WORK/stage3.c missing (run compiler/bootstrap.sh first)" >&2; exit 1; }

NAME="mini-$VERSION-$TARGET"
STAGE="$(mktemp -d)/$NAME"
mkdir -p "$STAGE/bin" "$STAGE/lib" "$STAGE/include"

echo "== minic-native (-O2)"
"$CC" -std=c17 -O2 -w -I "$ROOT/runtime/include" "$WORK/stage3.c" "$ROOT"/runtime/src/*.c -o "$STAGE/bin/minic-native" -lm

echo "== mini (-O2, compiled by the minic-native above)"
MINI_BUILD="$(mktemp -d)"
"$ROOT/scripts/build-mini.sh" --minic-native "$STAGE/bin/minic-native" --out "$MINI_BUILD" --opt -O2 --quiet
cp "$MINI_BUILD/bin/mini" "$STAGE/bin/mini"
rm -rf "$MINI_BUILD"

echo "== libminirt.a (-O2)"
OBJ="$(mktemp -d)"
for f in "$ROOT"/runtime/src/*.c; do
    "$CC" -std=c17 -O2 -w -I "$ROOT/runtime/include" -c "$f" -o "$OBJ/$(basename "$f" .c).o"
done
# entry.c holds `main`; it stays in the archive (the linker only pulls it in when the program has no main of its own)
ar rcs "$STAGE/lib/libminirt.a" "$OBJ"/*.o
cp "$ROOT/runtime/include/"*.h "$STAGE/include/"
cp -R "$ROOT/stdlib" "$STAGE/stdlib"
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$STAGE/"

# the driver script lives in scripts/minic.sh (help, run/check/build, diagnostics options, C compiler discovery)
cp "$ROOT/scripts/minic.sh" "$STAGE/bin/minic"
chmod +x "$STAGE/bin/minic"
echo "$VERSION" > "$STAGE/VERSION"
echo "$TARGET" > "$STAGE/TARGET"

cat > "$STAGE/README.md" <<EOF
# Mini $VERSION ($TARGET)

Kotlin compiled to native code. Needs a C compiler (clang or gcc) on the PATH; no JDK, no Gradle.

    export PATH="\$PWD/bin:\$PATH"
    mini new hello && cd hello && mini run        # a project: mini new, build, run, test, check, ...
    minic run hello.kt                            # or a single file: build and run
    minic build hello.kt                          # ./hello

* \`bin/mini\`          the project tool, itself compiled by Mini (\`mini help\` lists the commands)
* \`bin/minic\`         driver (\`build\`, \`run\`, \`check\`, \`emit-c\`, \`--version\`); runs \`bin/minic-native\`, then \`cc\` with \`lib/libminirt.a\`
* \`bin/minic-native\`  the self-hosted compiler: \`minic-native [-o out.c] [--emit-mir out.mir] <user.kt>... --stdlib <files>...\`
* \`lib/libminirt.a\`, \`include/\`  the C runtime
* \`stdlib/\`           the standard library sources, compiled together with your program

Project: https://github.com/HenrySchwerdt/mini, documentation: https://henryschwerdt.github.io/mini/. Apache-2.0, see LICENSE and NOTICE.
EOF

tar -C "$(dirname "$STAGE")" -czf "$OUT/$NAME.tar.gz" "$NAME"
echo "wrote $OUT/$NAME.tar.gz"

if [ "$SEED" = 1 ]; then
    "$ROOT/scripts/package-seed.sh" "$VERSION" "$WORK/stage3.c" "$OUT"
fi
