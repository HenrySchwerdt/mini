#!/usr/bin/env bash
# Builds the self-contained LLVM C API release tarball of one platform and (with --seed) the C bootstrap seed.
#
#   scripts/package-release.sh <version> <target> [--out <dir>] [--work <bootstrap-work-dir>] [--llvm-license <file>] [--zstd-license <file>] [--seed]
#
#   version   e.g. 0.4.0                         target  linux-x64 | linux-arm64 | macos-arm64 (any name)
#   --work    a work dir of compiler/bootstrap.sh holding stage3 (API compiler) and stage3.c; without it the static API bootstrap
#             runs first and checks stage2 == stage3.
#   --llvm-license the LLVM 18 LICENSE.TXT shipped in the release archive as required third-party license metadata.
#   --zstd-license the zstd BSD license/copyright text shipped with the installed package.
#   --out     where the archives go (default dist/)
#   --seed    also write mini-<version>-bootstrap-c.tar.gz (see scripts/package-seed.sh)
#
# Output: <out>/mini-<version>-<target>.tar.gz containing
#   bin/minic          shell driver (scripts/minic.sh): build, run, check, emit-c (compiler, then cc with the bundled runtime)
#   bin/minic-native   the self-hosted native compiler (the C of stage3, built with -O2)
#   bin/mini           the project tool (new, build, run, test, check, ...): Kotlin compiled by bin/minic-native, no JVM
#   lib/libminirt.a    the runtime, prebuilt;  include/minirt.h
#   lib/stdlib.mlib    the precompiled standard library (docs/mlib.md), used by default by minic and mini
#   stdlib/            the standard library sources (compiled together with every program)
#   LICENSE NOTICE LLVM-LICENSE.txt ZSTD-LICENSE.txt README.md
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:?usage: package-release.sh <version> <target> [--out dir] [--work dir] --llvm-license <file> --zstd-license <file> [--seed]}"
TARGET="${2:?missing target}"
shift 2
OUT="$ROOT/dist"
WORK=""
SEED=0
LLVM_LICENSE=""
ZSTD_LICENSE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT="$(mkdir -p "$2" && cd "$2" && pwd)"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --llvm-license) LLVM_LICENSE="$2"; shift 2 ;;
        --zstd-license) ZSTD_LICENSE="$2"; shift 2 ;;
        --seed) SEED=1; shift ;;
        *) echo "package-release.sh: unknown argument $1" >&2; exit 2 ;;
    esac
done
CC="${CC:-clang}"
mkdir -p "$OUT"

if [ -z "$WORK" ]; then
    WORK="$(mktemp -d)"
    BOOT_OPT=-O2 "$ROOT/compiler/bootstrap.sh" --backend=llvm-api "$WORK"
fi
[ -f "$WORK/stage3.c" ] || { echo "package-release.sh: $WORK/stage3.c missing (run compiler/bootstrap.sh first)" >&2; exit 1; }
[ -x "$WORK/stage3" ] || { echo "package-release.sh: $WORK/stage3 missing (run compiler/bootstrap.sh --backend=llvm-api first)" >&2; exit 1; }
if [ "$SEED" = 1 ] && [ ! -f "$WORK/plain.c" ]; then
    echo "package-release.sh: $WORK/plain.c missing (the portable seed needs the plain compiler from the LLVM API bootstrap)" >&2
    exit 1
fi
[ -f "$LLVM_LICENSE" ] || { echo "package-release.sh: pass --llvm-license <LLVM 18 LICENSE.TXT>" >&2; exit 1; }
[ -f "$ZSTD_LICENSE" ] || { echo "package-release.sh: pass --zstd-license <zstd BSD license/copyright>" >&2; exit 1; }

NAME="mini-$VERSION-$TARGET"
STAGE="$(mktemp -d)/$NAME"
mkdir -p "$STAGE/bin" "$STAGE/lib" "$STAGE/include"

echo "== minic-native (static LLVM C API)"
cp "$WORK/stage3" "$STAGE/bin/minic-native"
chmod +x "$STAGE/bin/minic-native"

echo "== mini (-O2, compiled by the minic-native above)"
MINI_BUILD="$(mktemp -d)"
"$ROOT/scripts/build-mini.sh" --minic-native "$STAGE/bin/minic-native" --backend llvm-api --out "$MINI_BUILD" --opt -O2 --quiet
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

echo "== stdlib.mlib (the precompiled standard library, built by the minic-native above)"
# relative paths (stdlib/kotlin/...), as in the release's stdlib/ directory
# shellcheck disable=SC2046
(cd "$ROOT" && "$STAGE/bin/minic-native" --emit-mlib "$STAGE/lib/stdlib.mlib" --name stdlib --compiler-id "$VERSION" --no-memory-check $(find stdlib -name '*.kt' | LC_ALL=C sort))
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$STAGE/"
cp "$LLVM_LICENSE" "$STAGE/LLVM-LICENSE.txt"
cp "$ZSTD_LICENSE" "$STAGE/ZSTD-LICENSE.txt"

# the driver script lives in scripts/minic.sh (help, run/check/build, diagnostics options, C compiler discovery)
cp "$ROOT/scripts/minic.sh" "$STAGE/bin/minic"
chmod +x "$STAGE/bin/minic"
echo "$VERSION" > "$STAGE/VERSION"
echo "$TARGET" > "$STAGE/TARGET"
echo "llvm-api" > "$STAGE/TOOLCHAIN-BACKEND"

cat > "$STAGE/README.md" <<EOF
# Mini $VERSION ($TARGET)

Kotlin compiled to native code. \`minic-native\` includes LLVM 18 statically; no JDK, Gradle, LLVM or C compiler is needed at runtime.

    export PATH="\$PWD/bin:\$PATH"
    mini new hello && cd hello && mini run        # a project: mini new, build, run, test, check, ...
    minic run hello.kt                            # or a single file: build and run
    minic build hello.kt                          # ./hello

* \`bin/mini\`          the project tool, itself compiled by Mini (\`mini help\` lists the commands)
* \`bin/minic\`         driver (\`build\`, \`run\`, \`check\`, \`emit-c\`, \`--version\`); LLVM API is the default, \`--backend=c\` selects C fallback
* \`bin/minic-native\`  the self-hosted compiler with the LLVM C API, C emitter, and ORC JIT REPL
* \`lib/libminirt.a\`, \`include/\`  the C runtime
* \`lib/stdlib.mlib\`   the precompiled standard library (used by default); \`stdlib/\` holds its sources (\`MINIC_STDLIB=source\` compiles them with the program)
* \`LLVM-LICENSE.txt\`  LLVM third-party license
* \`ZSTD-LICENSE.txt\`  zstd BSD license/copyright

Project: https://github.com/HenrySchwerdt/mini, documentation: https://henryschwerdt.github.io/mini/. Apache-2.0, see LICENSE and NOTICE.
EOF

tar -C "$(dirname "$STAGE")" -czf "$OUT/$NAME.tar.gz" "$NAME"
echo "wrote $OUT/$NAME.tar.gz"

if [ "$SEED" = 1 ]; then
    # stage3.c includes the LLVM API bindings; plain.c is the bootstrap-verified compiler requiring only C.
    "$ROOT/scripts/package-seed.sh" "$VERSION" "$WORK/plain.c" "$OUT"
fi
