#!/bin/bash
# B3 bootstrap: the compiler (frontend, lower, backend, minic-native driver) builds itself without K2.
#
#   stage1 = JVM minic --frontend=mini compiles the compiler sources            (own frontend on the JVM)
#   stage2 = stage1 compiles the same sources to C, clang builds it
#   stage3 = stage2 compiles the same sources to C, clang builds it
#
# Check: the C and MIR written by stage2 and stage3 are byte-identical. Wall times per stage are printed.
# Needs `./gradlew installDist` (compiler/jvm-bridge/build/install/minic) and clang. Usage: compiler/bootstrap.sh [work-dir]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
WORK="${1:-$(mktemp -d)}"
MINIC="$HERE/jvm-bridge/build/install/minic/bin/minic"
CC="${CC:-clang}"
export JAVA_OPTS="${JAVA_OPTS:--Xss512m -Xmx6g}"
mkdir -p "$WORK"

SRCS=$(find "$HERE/minic-native/src/main/kotlin" "$HERE/lower/src/main/kotlin" "$HERE/frontend/src/main/kotlin" "$HERE/backend/src/main/kotlin" -name '*.kt' | sort)
STD=$(find "$ROOT/stdlib" -name '*.kt' | sort)
RT=$(ls "$ROOT"/runtime/src/*.c)

stamp() { date +%s.%N; }
elapsed() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f s", b - a }'; }

t0=$(stamp)
"$MINIC" build $SRCS -o "$WORK/stage1" --frontend=mini --emit-c "$WORK/stage1.c"
t1=$(stamp)
echo "stage1 (JVM minic --frontend=mini builds the compiler): $(elapsed $t0 $t1)"

"$WORK/stage1" -o "$WORK/stage2.c" --emit-mir "$WORK/stage2.mir" $SRCS --stdlib $STD
t2=$(stamp)
"$CC" -std=c17 -O1 -w -I "$ROOT/runtime/include" "$WORK/stage2.c" $RT -o "$WORK/stage2" -lm
t3=$(stamp)
echo "stage2 (stage1 compiles the sources: $(elapsed $t1 $t2); clang: $(elapsed $t2 $t3))"

"$WORK/stage2" -o "$WORK/stage3.c" --emit-mir "$WORK/stage3.mir" $SRCS --stdlib $STD
t4=$(stamp)
"$CC" -std=c17 -O1 -w -I "$ROOT/runtime/include" "$WORK/stage3.c" $RT -o "$WORK/stage3" -lm
t5=$(stamp)
echo "stage3 (stage2 compiles the sources: $(elapsed $t3 $t4); clang: $(elapsed $t4 $t5))"

# the fixpoint: stage2 and stage3 are the same compiler
cmp "$WORK/stage2.c" "$WORK/stage3.c"
cmp "$WORK/stage2.mir" "$WORK/stage3.mir"
echo "bootstrap OK: stage2 == stage3 (C and MIR byte-identical), work dir $WORK"
