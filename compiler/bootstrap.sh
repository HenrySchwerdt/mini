#!/bin/bash
# B3 bootstrap: the compiler (frontend, lower, backend, minic-native driver) builds itself without K2.
#
#   stage1 = JVM minic --frontend=mini compiles the compiler sources            (own frontend on the JVM)
#   stage2 = stage1 compiles the same sources to C, clang builds it
#   stage3 = stage2 compiles the same sources to C, clang builds it
#
# Check: the C and MIR written by stage2 and stage3 are byte-identical. Wall times per stage are printed.
# Needs `./gradlew installDist` (compiler/jvm-bridge/build/install/minic) and clang.
#
# Usage: compiler/bootstrap.sh [--stage0 <native-minic>] [work-dir]
#
# With --stage0 the JVM is not needed at all: a prebuilt native compiler (a release's bin/minic-native, or the
# stage2 of the bootstrap seed) takes the place of the JVM stage1 and compiles the current sources to C:
#   stage0 (given, native) -> stage1 (native) -> stage2 -> stage3, and stage2 == stage3 is checked.
# stage0 may come from an older compiler version, so stage1 is not compared (stage2 == stage3 is the fixpoint).
# Environment: CC (default clang), BOOT_OPT (default -O1, -O0 with --stage0, the optimization level of the built stages).
set -e
STAGE0=""
if [ "$1" = "--stage0" ]; then
    if [ -z "$2" ] || [ ! -x "$2" ]; then echo "bootstrap.sh: --stage0 needs an executable native minic" >&2; exit 2; fi
    STAGE0="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
    shift 2
fi
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
WORK="${1:-$(mktemp -d)}"
MINIC="$HERE/jvm-bridge/build/install/minic/bin/minic"
CC="${CC:-clang}"
OPT="${BOOT_OPT:--O1}"
[ -n "$STAGE0" ] && OPT="${BOOT_OPT:--O0}"  # nothing is benchmarked here: clang -O0 takes 9 s instead of 73 s per stage
export JAVA_OPTS="${JAVA_OPTS:--Xss512m -Xmx6g}"
mkdir -p "$WORK"

SRCS=$(find "$HERE/minic-native/src/main/kotlin" "$HERE/lower/src/main/kotlin" "$HERE/frontend/src/main/kotlin" "$HERE/backend/src/main/kotlin" -name '*.kt' | sort)
STD=$(find "$ROOT/stdlib" -name '*.kt' | sort)
RT=$(ls "$ROOT"/runtime/src/*.c)

stamp() { date +%s.%N; }
elapsed() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f s", b - a }'; }

t0=$(stamp)
if [ -n "$STAGE0" ]; then
    "$STAGE0" -o "$WORK/stage1.c" $SRCS --stdlib $STD
    t0b=$(stamp)
    "$CC" -std=c17 $OPT -w -I "$ROOT/runtime/include" "$WORK/stage1.c" $RT -o "$WORK/stage1" -lm
    t1=$(stamp)
    echo "stage1 (native stage0 $STAGE0 compiles the sources, no JVM: $(elapsed $t0 $t0b); clang: $(elapsed $t0b $t1))"
else
    "$MINIC" build $SRCS -o "$WORK/stage1" --frontend=mini --emit-c "$WORK/stage1.c"
    t1=$(stamp)
    echo "stage1 (JVM minic --frontend=mini builds the compiler): $(elapsed $t0 $t1)"
fi

"$WORK/stage1" -o "$WORK/stage2.c" --emit-mir "$WORK/stage2.mir" $SRCS --stdlib $STD
t2=$(stamp)
"$CC" -std=c17 $OPT -w -I "$ROOT/runtime/include" "$WORK/stage2.c" $RT -o "$WORK/stage2" -lm
t3=$(stamp)
echo "stage2 (stage1 compiles the sources: $(elapsed $t1 $t2); clang: $(elapsed $t2 $t3))"

"$WORK/stage2" -o "$WORK/stage3.c" --emit-mir "$WORK/stage3.mir" $SRCS --stdlib $STD
t4=$(stamp)
"$CC" -std=c17 $OPT -w -I "$ROOT/runtime/include" "$WORK/stage3.c" $RT -o "$WORK/stage3" -lm
t5=$(stamp)
echo "stage3 (stage2 compiles the sources: $(elapsed $t3 $t4); clang: $(elapsed $t4 $t5))"

# the fixpoint: stage2 and stage3 are the same compiler
cmp "$WORK/stage2.c" "$WORK/stage3.c"
cmp "$WORK/stage2.mir" "$WORK/stage3.mir"
echo "bootstrap OK: stage2 == stage3 (C and MIR byte-identical), work dir $WORK"
