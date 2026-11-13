#!/usr/bin/env bash
# Builds the libFuzzer harnesses (docs/fuzzing.md, "libFuzzer harnesses").
#
#   tools/fuzz/libfuzzer/build.sh [--out DIR] [--native DIR] [--cc clang] [harness...]
#
# harness: utf8 numfmt (C: runtime functions) and parse numstr format regex text (mini targets of targets/*.kt, compiled to C with minic-native
# against lib/stdlib.mlib and linked with the runtime). All of it is built with -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=undefined,
# the runtime included. Binaries: DIR/fuzz_NAME. Needs clang with libFuzzer (compiler-rt). FUZZ_STDLIB=source compiles the targets against the
# stdlib sources instead of lib/stdlib.mlib.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="${TMPDIR:-/tmp}/mini-libfuzzer" NATIVE="$ROOT/build/native" CC="${CC:-}"
WANT=()
while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT="$2"; shift 2 ;;
        --native) NATIVE="$2"; shift 2 ;;
        --cc) CC="$2"; shift 2 ;;
        *) WANT+=("$1"); shift ;;
    esac
done
[ ${#WANT[@]} -gt 0 ] || WANT=(utf8 numfmt parse numstr format regex text)
MINIC="$NATIVE/bin/minic-native"
[ -x "$MINIC" ] || { echo "build.sh: $MINIC not found (scripts/build-mini.sh)" >&2; exit 3; }
mkdir -p "$OUT"
# a clang that links libFuzzer + ASan + UBSan: $CC, clang, or the clang of the Kotlin/Native LLVM dependency (~/.konan)
echo 'int LLVMFuzzerTestOneInput(const unsigned char *d, unsigned long n) { (void)d; return (int)n; }' > "$OUT/probe.c"
if [ -z "$CC" ]; then
    for cand in clang $(ls "$HOME"/.konan/dependencies/llvm-*/bin/clang 2>/dev/null); do
        command -v "$cand" >/dev/null 2>&1 || continue
        if "$cand" -fsanitize=fuzzer,address,undefined "$OUT/probe.c" -o "$OUT/probe" >/dev/null 2>&1; then CC="$cand"; break; fi
    done
fi
[ -n "$CC" ] || { echo "build.sh: no clang with libFuzzer, ASan and UBSan runtimes (set CC or --cc)" >&2; exit 3; }
# <sanitizer/asan_interface.h> is missing from some clang installs: take the one of gcc
EXTRA=""
if ! echo '#include <sanitizer/asan_interface.h>' | "$CC" -x c -fsyntax-only - >/dev/null 2>&1; then
    g="$(gcc -print-file-name=include 2>/dev/null || true)"; [ -f "$g/sanitizer/asan_interface.h" ] && EXTRA="-I $g"
fi
SAN="-fsanitize=address,undefined -fno-sanitize-recover=undefined"
FLAGS="-std=c17 -g -O1 -fno-omit-frame-pointer $SAN $EXTRA"
RTSRC="$ROOT/runtime/src" INC="$ROOT/runtime/include"
# the runtime, instrumented for coverage as well (without entry.c: libFuzzer brings main)
if [ ! -f "$OUT/rt/.done" ]; then
    rm -rf "$OUT/rt"; mkdir -p "$OUT/rt"
    pids=()
    for f in "$RTSRC"/*.c; do
        b="$(basename "$f" .c)"; [ "$b" = entry ] && continue
        "$CC" $FLAGS -fsanitize=fuzzer-no-link -Wall -Wextra -Werror -I "$INC" -I "$RTSRC" -c "$f" -o "$OUT/rt/$b.o" &
        pids+=($!)
    done
    for p in "${pids[@]}"; do wait "$p" || { rm -rf "$OUT/rt"; echo "build.sh: the runtime does not build" >&2; exit 1; }; done
    ls "$OUT"/rt/*.o | wc -l > "$OUT/rt/.done"
fi
for h in "${WANT[@]}"; do
    case "$h" in
        utf8|numfmt)
            "$CC" $FLAGS -fsanitize=fuzzer -Wall -Wextra -Werror -I "$INC" -I "$RTSRC" "$HERE/fuzz_$h.c" "$OUT"/rt/*.o -o "$OUT/fuzz_$h" -lm -lpthread ;;
        parse|numstr|format|regex|text)
            if [ "${FUZZ_STDLIB:-mlib}" = source ]; then # the checkout's stdlib sources (after a stdlib change, without rebuilding stdlib.mlib)
                "$MINIC" -o "$OUT/$h.c" "$HERE/targets/$h.kt" --stdlib $(find "$ROOT/stdlib" -name '*.kt' | LC_ALL=C sort)
            else
                "$MINIC" -o "$OUT/$h.c" "$HERE/targets/$h.kt" --lib "$NATIVE/lib/stdlib.mlib" --compiler-id "$(cat "$NATIVE/VERSION")"
            fi
            fn="$(grep -o -m1 'kt_fuzz[A-Za-z]*_[A-Za-z0-9_]*' "$OUT/$h.c" | head -1)"
            "$CC" $FLAGS -fsanitize=fuzzer -w -I "$INC" -I "$RTSRC" -I "$OUT" -DFZ_GEN="\"$h.c\"" -DFZ_FN="$fn" "$HERE/fuzz_kt.c" "$OUT"/rt/*.o -o "$OUT/fuzz_$h" -lm -lpthread ;;
        *) echo "build.sh: unknown harness $h" >&2; exit 2 ;;
    esac
    echo "built $OUT/fuzz_$h"
done
