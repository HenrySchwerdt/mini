#!/usr/bin/env bash
# Builds the release tarball of one platform and (with --seed) the platform-independent bootstrap seed.
#
#   scripts/package-release.sh <version> <target> [--out <dir>] [--work <bootstrap-work-dir>] [--seed]
#
#   version   e.g. 0.4.0                         target  linux-x64 | linux-arm64 | macos-arm64 (any name)
#   --work    a work dir of compiler/bootstrap.sh holding stage3.c; without it the bootstrap runs first
#             (needs the JVM stage1: ./gradlew :jvm-bridge:installDist) and checks stage2 == stage3.
#   --out     where the archives go (default dist/)
#   --seed    also write mini-<version>-bootstrap-c.tar.gz (see scripts/package-seed.sh)
#
# Output: <out>/mini-<version>-<target>.tar.gz containing
#   bin/minic          shell wrapper: `minic build foo.kt -o foo` (compiler, then cc with the bundled runtime)
#   bin/minic-native   the self-hosted native compiler (the C of stage3, built with -O2)
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

cat > "$STAGE/bin/minic" <<'WRAPPER'
#!/bin/sh
# minic: Kotlin/Mini to a native binary.  minic build foo.kt [more.kt ...] -o foo [-O0|-O1|-O2|-O3] [--emit-c foo.c] [--cc cc]
# v0 wrapper: runs minic-native (Kotlin to C), then the C compiler with the bundled libminirt.
set -e
self="$0"
while [ -h "$self" ]; do
    dir="$(cd "$(dirname "$self")" && pwd)"
    self="$(readlink "$self")"
    case "$self" in /*) ;; *) self="$dir/$self" ;; esac
done
HOME_DIR="$(cd "$(dirname "$self")/.." && pwd)"
usage() {
    cat >&2 <<EOF
usage: minic build <file.kt>... -o <out> [-O0|-O1|-O2|-O3] [--emit-c <out.c>] [--cc <compiler>]
       minic emit-c <file.kt>... -o <out.c>
       minic version
Environment: CC (default: cc), MINI_HOME (default: the directory above bin/)
EOF
    exit 2
}
cmd="${1:-}"
[ -n "$cmd" ] || usage
shift
case "$cmd" in
    version|--version) cat "$HOME_DIR/VERSION" 2>/dev/null || echo unknown; exit 0 ;;
    build|emit-c) ;;
    -h|--help|help) usage ;;
    *) echo "minic: unknown command '$cmd'" >&2; usage ;;
esac
out=""; emit=""; opt="-O1"; cc="${CC:-cc}"; srcs=""
while [ $# -gt 0 ]; do
    case "$1" in
        -o) [ $# -ge 2 ] || usage; out="$2"; shift 2 ;;
        --emit-c) [ $# -ge 2 ] || usage; emit="$2"; shift 2 ;;
        --cc) [ $# -ge 2 ] || usage; cc="$2"; shift 2 ;;
        -O0|-O1|-O2|-O3|-Os) opt="$1"; shift ;;
        -*) echo "minic: unknown option $1" >&2; usage ;;
        *) srcs="$srcs $1"; shift ;;
    esac
done
[ -n "$srcs" ] && [ -n "$out" ] || usage
STD="$(find "$HOME_DIR/stdlib" -name '*.kt' | LC_ALL=C sort)"
if [ "$cmd" = emit-c ]; then
    # shellcheck disable=SC2086
    exec "$HOME_DIR/bin/minic-native" -o "$out" $srcs --stdlib $STD
fi
if [ -n "$emit" ]; then cfile="$emit"; else cfile="$(mktemp "${TMPDIR:-/tmp}/minic.XXXXXX")"; mv "$cfile" "$cfile.c"; cfile="$cfile.c"; trap 'rm -f "$cfile"' EXIT; fi
# shellcheck disable=SC2086
"$HOME_DIR/bin/minic-native" -o "$cfile" $srcs --stdlib $STD
"$cc" -std=c17 "$opt" -w -I "$HOME_DIR/include" "$cfile" "$HOME_DIR/lib/libminirt.a" -lm -o "$out"
WRAPPER
chmod +x "$STAGE/bin/minic"
echo "$VERSION" > "$STAGE/VERSION"

cat > "$STAGE/README.md" <<EOF
# Mini $VERSION ($TARGET)

Kotlin compiled to native code. Needs a C compiler (clang or gcc) on the PATH; no JDK.

    export PATH="\$PWD/bin:\$PATH"
    minic build hello.kt -o hello && ./hello

* \`bin/minic\`         build driver (\`build\`, \`emit-c\`, \`version\`); runs \`bin/minic-native\`, then \`cc\` with \`lib/libminirt.a\`
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
