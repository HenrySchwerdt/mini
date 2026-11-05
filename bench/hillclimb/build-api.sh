#!/usr/bin/env bash
# Builds the LLVM-C-API-enabled toolchain of this checkout into build/api (bin/minic-native with the API, bin/minic, lib/...),
# next to the plain C-built one in build/native (scripts/build-mini.sh). Needs llvm-config 18, a native stage0 (MINI_STAGE0, default
# build/native/bin/minic-native or any release) and a C compiler. Mirrors stage1 of `compiler/bootstrap.sh --backend=llvm-api`.
#   bench/hillclimb/build-api.sh [--quiet]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CC="${CC:-clang}"
OUT="$ROOT/build/api"
STAGE0="${MINI_STAGE0:-$ROOT/build/native/bin/minic-native}"
mkdir -p "$OUT/work" "$OUT/bin"
HERE="$ROOT/compiler"
SRCS="$(find "$HERE/minic-native/src" -name '*.kt' ! -name Main.kt | sort; find "$HERE/lower/src" "$HERE/frontend/src" "$HERE/backend/src" "$HERE/llvm-c/src" -name '*.kt' | sort)"
STD="$(find "$ROOT/stdlib" -name '*.kt' | sort)"
newer() { local t="$1"; shift; [ -e "$t" ] || return 0; [ -n "$(find "$@" -type f -newer "$t" -print -quit)" ]; }
if newer "$OUT/bin/minic-native" "$HERE/minic-native/src" "$HERE/lower/src" "$HERE/frontend/src" "$HERE/backend/src" "$HERE/llvm-c/src" "$ROOT/stdlib" "$ROOT/runtime/src" "$ROOT/runtime/include"; then
    # shellcheck disable=SC2086
    "$STAGE0" --backend=c -o "$OUT/work/api.c" $SRCS --stdlib $STD
    LIBS=($(llvm-config --link-static --libs core native orcjit passes x86 aarch64))
    SYS=($(llvm-config --link-static --system-libs | tr ' ' '\n' | grep -v '^-lzstd$'))
    "$CC" -std=c17 -O1 -w -I "$ROOT/runtime/include" -I"$(llvm-config --includedir)" "$OUT/work/api.c" "$ROOT"/runtime/src/*.c \
        -L"$(llvm-config --libdir)" "${LIBS[@]}" -Wl,--as-needed "${SYS[@]}" -Wl,-Bstatic -lzstd -Wl,-Bdynamic -lstdc++ -rdynamic -lm -o "$OUT/work/minic-native.new"
    rm -f "$OUT/work/api.c"
    "$ROOT/scripts/build-mini.sh" --out "$OUT" --minic-native "$OUT/work/minic-native.new" --backend llvm-api "$@"
    rm -f "$OUT/work/minic-native.new"
else
    "$ROOT/scripts/build-mini.sh" --out "$OUT" --backend llvm-api "$@"
fi
