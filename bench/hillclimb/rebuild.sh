#!/usr/bin/env bash
# Fast rebuild of the compiler for the hillclimb: the compiler sources are compiled to C by a stage0 (MINI_STAGE0, default: the
# release in /root/stage0) and the C is built with clang -O0 (about 20 s instead of 70 s; the generated programs are unaffected, only
# the compiler itself runs slower), then lib/libminirt.a (-O1, as in scripts/build-mini.sh) and lib/stdlib.mlib are refreshed.
#   bench/hillclimb/rebuild.sh [c|api]       c: build/native (plain C-backend compiler), api: build/api (LLVM C API compiler)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CC="${CC:-clang}"
KIND="${1:-c}"
S0="${MINI_STAGE0:-/root/stage0/mini-0.9.0-linux-x64/bin/minic-native}"
OPT="${HC_COMPILER_OPT:--O0}"
H="$ROOT/compiler"
STD="$(find "$ROOT/stdlib" -name '*.kt' | LC_ALL=C sort)"
if [ "$KIND" = api ]; then
    OUT="$ROOT/build/api"
    SRCS="$(find "$H/minic-native/src" -name '*.kt' ! -name Main.kt | LC_ALL=C sort; find "$H/lower/src" "$H/frontend/src" "$H/backend/src" "$H/llvm-c/src" -name '*.kt' | LC_ALL=C sort)"
else
    OUT="$ROOT/build/native"
    SRCS="$(find "$H/minic-native/src" "$H/lower/src" "$H/frontend/src" "$H/backend/src" -name '*.kt' | LC_ALL=C sort)"
fi
mkdir -p "$OUT/work" "$OUT/bin"
# shellcheck disable=SC2086
"$S0" --backend=c -o "$OUT/work/dev.c" $SRCS --stdlib $STD
if [ "$KIND" = api ]; then
    LIBS=($(llvm-config --link-static --libs core native orcjit passes x86 aarch64))
    SYS=($(llvm-config --link-static --system-libs | tr ' ' '\n' | grep -v '^-lzstd$'))
    "$CC" -std=c17 "$OPT" -w -I "$ROOT/runtime/include" -I"$(llvm-config --includedir)" "$OUT/work/dev.c" "$ROOT"/runtime/src/*.c \
        -L"$(llvm-config --libdir)" "${LIBS[@]}" -Wl,--as-needed "${SYS[@]}" -Wl,-Bstatic -lzstd -Wl,-Bdynamic -lstdc++ -rdynamic -lm -o "$OUT/work/minic-native.new"
else
    "$CC" -std=c17 "$OPT" -w -I "$ROOT/runtime/include" "$OUT/work/dev.c" "$ROOT"/runtime/src/*.c -o "$OUT/work/minic-native.new" -lm
fi
rm -f "$OUT/work/dev.c"
"$ROOT/scripts/build-mini.sh" --out "$OUT" --minic-native "$OUT/work/minic-native.new" --skip-tool --quiet $([ "$KIND" = api ] && echo "--backend llvm-api")
rm -f "$OUT/work/minic-native.new"
