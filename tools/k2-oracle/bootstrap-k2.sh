#!/bin/bash
# The K2 bootstrap path: the compiler is built by the JVM compiler (official K2 frontend or the own frontend on the JVM), then
# builds itself natively. This is the original B3 bootstrap; the main build starts from a native stage0 instead
# (compiler/bootstrap.sh) and never needs the JVM.
#
#   stage1 = JVM minic --frontend=mini compiles the compiler sources         (own frontend on the JVM)
#   stage2 = stage1 compiles the same sources to C, the C compiler builds it
#   stage3 = stage2 compiles the same sources to C, the C compiler builds it
# Checks: stage2 == stage3 (C and MIR byte-identical), and, with --reference <work dir of compiler/bootstrap.sh>, that the
# JVM-started chain reaches the same fixpoint as the native-started one.
#
#   tools/k2-oracle/bootstrap-k2.sh [--reference <dir>] [work-dir]      needs ./gradlew :jvm-bridge:installDist first
set -e
REFERENCE=""
if [ "$1" = "--reference" ]; then REFERENCE="$2"; shift 2; fi
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$(dirname "$HERE")")"
WORK="${1:-$(mktemp -d)}"
MINIC="$HERE/jvm-bridge/build/install/minic/bin/minic"
[ -x "$MINIC" ] || { echo "bootstrap-k2.sh: build the JVM compiler first: (cd tools/k2-oracle && ./gradlew :jvm-bridge:installDist)" >&2; exit 2; }
CC="${CC:-clang}"
OPT="${BOOT_OPT:--O0}"
export JAVA_OPTS="${JAVA_OPTS:--Xss512m -Xmx6g}"
mkdir -p "$WORK"
SRCS=$(find "$ROOT/compiler/minic-native/src" "$ROOT/compiler/lower/src" "$ROOT/compiler/frontend/src" "$ROOT/compiler/backend/src" -name '*.kt' | sort)
STD=$(find "$ROOT/stdlib" -name '*.kt' | sort)
RT=$(ls "$ROOT"/runtime/src/*.c)

"$MINIC" build $SRCS -o "$WORK/stage1" --frontend=mini --emit-c "$WORK/stage1.c"
echo "stage1: JVM minic --frontend=mini built the compiler"
"$WORK/stage1" -o "$WORK/stage2.c" --emit-mir "$WORK/stage2.mir" $SRCS --stdlib $STD
"$CC" -std=c17 $OPT -w -I "$ROOT/runtime/include" "$WORK/stage2.c" $RT -o "$WORK/stage2" -lm
"$WORK/stage2" -o "$WORK/stage3.c" --emit-mir "$WORK/stage3.mir" $SRCS --stdlib $STD
"$CC" -std=c17 $OPT -w -I "$ROOT/runtime/include" "$WORK/stage3.c" $RT -o "$WORK/stage3" -lm
cmp "$WORK/stage2.c" "$WORK/stage3.c"
cmp "$WORK/stage2.mir" "$WORK/stage3.mir"
echo "K2/JVM-started bootstrap OK: stage2 == stage3 (C and MIR byte-identical), work dir $WORK"
if [ -n "$REFERENCE" ]; then
    cmp "$WORK/stage2.c" "$REFERENCE/stage2.c"
    cmp "$WORK/stage2.mir" "$REFERENCE/stage2.mir"
    echo "same C and MIR as the natively started chain in $REFERENCE"
fi
