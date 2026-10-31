#!/usr/bin/env bash
# Builds the native `mini` project tool (and, when needed, the native compiler) from source, with no JVM and no Gradle:
# `mini` is Kotlin compiled by Mini itself (compiler/mini-tool + compiler/frontend, with the native platform layer
# compiler/mini-tool/src/NativePlatform.kt + host/), exactly like the compiler's own self-host.
#
#   scripts/build-mini.sh [--out <dir>] [--minic-native <path> | --stage0 <path>] [--backend c|llvm-api] [--opt -O1|-O2] [--quiet]
#
# Output (default <repo>/build/native), the layout of a release archive:
#   bin/minic-native   the native compiler          bin/mini   the project tool          bin/minic  the driver script (scripts/minic.sh)
#   lib/stdlib.mlib    the precompiled standard library (docs/mlib.md)
#   VERSION            the toolchain version (the VERSION file of the repository)
#   lib/libminirt.a    the runtime                  include/   minirt.h        stdlib/  (a link to <repo>/stdlib)
#
# Where the compiler comes from, in this order:
#   --minic-native P   P is a compiler of the current sources (a release build, a bootstrap stage): it is copied and used as it is.
#   --stage0 P         P is any native compiler (e.g. the one of the latest release): it builds the current compiler (one stage).
#   otherwise          <out>/bin/minic-native when it is up to date; else a stage0 from $MINI_STAGE0, <repo>/build/stage0 (the
#                      latest release, see ./mini), ~/.mini, or PATH. Exit status 3 when there is none.
# `--backend llvm-api` builds mini through the in-process LLVM C API compiler and needs an API-enabled minic-native.
# The default C path remains for the C bootstrap seed and source checkouts without LLVM. CC is the system linker either way.
# Exit status: 0 ok, 3 no compiler to start from, else a build failure.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/native"
OPT="-O1"
GIVEN="" STAGE0="" QUIET=0 BACKEND=c
while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT="$(mkdir -p "$2" && cd "$2" && pwd)"; shift 2 ;;
        --minic-native) GIVEN="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift 2 ;;
        --stage0) STAGE0="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift 2 ;;
        --backend) BACKEND="$2"; shift 2 ;;
        --opt) OPT="$2"; shift 2 ;;
        --quiet) QUIET=1; shift ;;
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "build-mini.sh: unknown argument $1" >&2; exit 2 ;;
    esac
done
case "$BACKEND" in c|llvm-api) ;; *) echo "build-mini.sh: --backend must be c or llvm-api" >&2; exit 2 ;; esac
if [ -n "${CC:-}" ]; then :; elif command -v clang >/dev/null 2>&1; then CC=clang; else CC=cc; fi
mkdir -p "$OUT/bin" "$OUT/lib" "$OUT/include" "$OUT/work"
OUT="$(cd "$OUT" && pwd)"
say() { [ "$QUIET" = 1 ] || echo "build-mini: $*" >&2; }

COMPILER_DIRS=("$ROOT/compiler/minic-native/src" "$ROOT/compiler/lower/src" "$ROOT/compiler/frontend/src" "$ROOT/compiler/backend/src")
TOOL_DIRS=("$ROOT/compiler/mini-tool/src" "$ROOT/compiler/frontend/src")
HOST_C="$ROOT/compiler/mini-tool/host"

STD="$(find "$ROOT/stdlib" -name '*.kt' | LC_ALL=C sort)"
COMPILER_SRCS="$(find "${COMPILER_DIRS[@]}" -name '*.kt' | LC_ALL=C sort)"

# newer <target> <path>...: is any file below the paths newer than the target (or the target missing)?
newer() {
    local target="$1"; shift
    [ -e "$target" ] || return 0
    [ -n "$(find "$@" -type f -newer "$target" -print -quit 2>/dev/null)" ]
}

# --- stage the layout that does not depend on any compiler
rm -rf "$OUT/stdlib"
ln -s "$ROOT/stdlib" "$OUT/stdlib"
cp "$ROOT/runtime/include/"*.h "$OUT/include/"
cp "$ROOT/scripts/minic.sh" "$OUT/bin/minic"
cp "$ROOT/VERSION" "$OUT/VERSION"
chmod +x "$OUT/bin/minic"

build_runtime() {
    if ! newer "$OUT/lib/libminirt.a" "$ROOT/runtime/src" "$ROOT/runtime/include"; then return 0; fi
    say "libminirt.a ($OPT)"
    local obj="$OUT/work/rt"; rm -rf "$obj"; mkdir -p "$obj"
    for f in "$ROOT"/runtime/src/*.c; do
        "$CC" -std=c17 "$OPT" -w -I "$ROOT/runtime/include" -c "$f" -o "$obj/$(basename "$f" .c).o"
    done
    rm -f "$OUT/lib/libminirt.a"
    ar rcs "$OUT/lib/libminirt.a" "$obj"/*.o
}

# compile_with <minic-native> <out-binary> <c-opt>: the compiler of the current sources, built by the given native compiler
compile_compiler() {
    local stage0="$1" bin="$2"
    say "compiler: $stage0 compiles the compiler sources"
    # shellcheck disable=SC2086
    "$stage0" --backend=c -o "$OUT/work/minic.c" $COMPILER_SRCS --stdlib $STD
    "$CC" -std=c17 "$OPT" -w -I "$ROOT/runtime/include" "$OUT/work/minic.c" "$ROOT"/runtime/src/*.c -o "$bin.tmp" -lm
    mv "$bin.tmp" "$bin"
}

# --- the compiler
compiler_stale() { newer "$OUT/bin/minic-native" "${COMPILER_DIRS[@]}" "$ROOT/stdlib" "$ROOT/runtime/src" "$ROOT/runtime/include"; }

if [ -n "$GIVEN" ]; then
    [ "$GIVEN" = "$OUT/bin/minic-native" ] || cp "$GIVEN" "$OUT/bin/minic-native"
elif [ -n "$STAGE0" ]; then
    compile_compiler "$STAGE0" "$OUT/bin/minic-native"
elif compiler_stale; then
    s0="${MINI_STAGE0:-}"
    if [ -z "$s0" ]; then
        for cand in "$ROOT/build/stage0/bin/minic-native" "${MINI_HOME:-$HOME/.mini}/bin/minic-native" "$(command -v minic-native 2>/dev/null || true)"; do
            [ -n "$cand" ] && [ -x "$cand" ] && { s0="$cand"; break; }
        done
    fi
    if [ -z "$s0" ]; then
        echo "build-mini.sh: no native compiler to start from (give --stage0 <minic-native>, set MINI_STAGE0, or install a release: see install.sh)" >&2
        exit 3
    fi
    compile_compiler "$s0" "$OUT/bin/minic-native"
fi
build_runtime

# --- the precompiled standard library (docs/mlib.md): lib/stdlib.mlib, built by the compiler above
stdlib_mlib_stale() { newer "$OUT/lib/stdlib.mlib" "$ROOT/stdlib" "$OUT/bin/minic-native" "$ROOT/VERSION"; }
if stdlib_mlib_stale; then
    say "stdlib.mlib"
    # relative paths (stdlib/kotlin/...): what diagnostics and debuggers show for library code, whatever the checkout is called
    # shellcheck disable=SC2046
    (cd "$ROOT" && "$OUT/bin/minic-native" --emit-mlib "$OUT/lib/stdlib.mlib.tmp" --name stdlib --compiler-id "$(cat VERSION)" --no-memory-check $(find stdlib -name '*.kt' | LC_ALL=C sort))
    mv "$OUT/lib/stdlib.mlib.tmp" "$OUT/lib/stdlib.mlib"
fi

# --- mini itself, compiled by the native compiler
tool_stale() { newer "$OUT/bin/mini" "${TOOL_DIRS[@]}" "$HOST_C" "$ROOT/stdlib" "$ROOT/runtime/src" "$ROOT/runtime/include" "$OUT/bin/minic-native"; }
if tool_stale || [ -n "$GIVEN" ] || [ -n "$STAGE0" ]; then
    say "mini: compiled by minic-native (--backend=$BACKEND, $OPT)"
    TOOL_SRCS="$(find "${TOOL_DIRS[@]}" -name '*.kt' | LC_ALL=C sort)"
    "$CC" -std=gnu17 "$OPT" -w -I "$ROOT/runtime/include" -c "$HOST_C/mini_host.c" -o "$OUT/work/mini_host.o"
    if [ "$BACKEND" = llvm-api ]; then
        # The API compiler also contains CEmitter; wrappers pass the backend explicitly so the same installed compiler can
        # use LLVM by default while the seed's raw `minic-native -o out.c` remains compatible with the C bootstrap.
        # shellcheck disable=SC2086
        "$OUT/bin/minic-native" --backend=llvm-api --opt=2 -o "$OUT/work/mini.o" --exe "$OUT/bin/mini.tmp" --cc "$CC" \
            --link-arg "$OUT/work/mini_host.o" --link-arg "$OUT/lib/libminirt.a" $TOOL_SRCS --stdlib $STD
    else
        # shellcheck disable=SC2086
        "$OUT/bin/minic-native" --backend=c -o "$OUT/work/mini.c" $TOOL_SRCS --stdlib $STD
        "$CC" -std=c17 "$OPT" -w -I "$ROOT/runtime/include" -I "$HOST_C" "$OUT/work/mini.c" "$OUT/work/mini_host.o" "$OUT/lib/libminirt.a" -o "$OUT/bin/mini.tmp" -lm
    fi
    mv "$OUT/bin/mini.tmp" "$OUT/bin/mini"
fi
touch "$OUT/bin/mini"   # newer than every input: ./mini and ./minic use this to decide whether to rebuild
say "ready: $OUT/bin/mini"
printf '%s\n' "$BACKEND" > "$OUT/TOOLCHAIN-BACKEND"
