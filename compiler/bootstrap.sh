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
#
#   compiler/bootstrap.sh [--backend=llvm|llvm-api [--reference <dir>]] [work-dir]
#
# --backend=llvm (docs/plan-kotlin.md 10g): every stage is built through the LLVM backend (MIR -> textual LLVM IR ->
# clang) instead of C: stage1 = JVM minic --frontend=mini --backend=llvm; stage2 and stage3 are built from the .ll files
# written by the previous stage (`--emit-ll`). Check: C, MIR and LLVM IR of stage2 and stage3 are byte-identical; with
# --reference <dir> (the work dir of a C bootstrap) the C and MIR of stage2 must also equal the C-built compiler's.
#
# --backend=llvm-api (docs/plan-kotlin.md 10g phase 2): the compiler that has the LLVM C API bound in (compiler/llvm-c, needs the LLVM 18
# headers and libLLVM: `llvm-config`) builds itself with no text IR and no clang for the code: every stage compiles the sources to MIR,
# builds the LLVM module in memory and writes the object file through the target machine (`--backend=llvm-api -o x.o`); only the final link
# runs the system C compiler. stage1 = JVM minic builds the API compiler (llvm-c sources, mini.llvmapi.main instead of Main.kt); stage2 and
# stage3 are built by the previous stage. Check: C, MIR, the LLVM module text and the object file of stage2 and stage3 are byte-identical.
# Then stage3 builds the plain compiler (no llvm-c) through the API too; with --reference <dir> its C and MIR must equal the C-built chain's.
set -e
STAGE0=""
if [ "$1" = "--stage0" ]; then
    if [ -z "$2" ] || [ ! -x "$2" ]; then echo "bootstrap.sh: --stage0 needs an executable native minic" >&2; exit 2; fi
    STAGE0="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
    shift 2
fi
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
BACKEND=c
REFERENCE=""
while [ $# -gt 0 ]; do
    case "$1" in
    --backend=llvm) BACKEND=llvm; shift ;;
    --backend=llvm-api) BACKEND=llvm-api; shift ;;
    --backend=c) BACKEND=c; shift ;;
    --reference) REFERENCE="$2"; shift 2 ;;
    *) break ;;
    esac
done
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

if [ "$BACKEND" = llvm-api ]; then
    LLVM_INC="${LLVM_INC:-$(llvm-config --includedir)}"
    LLVM_LIB="${LLVM_LIB:-$(llvm-config --libdir)}"
    APISRCS=$(find "$HERE/minic-native/src/main/kotlin" -name '*.kt' ! -name Main.kt | sort; find "$HERE/lower/src/main/kotlin" "$HERE/frontend/src/main/kotlin" "$HERE/backend/src/main/kotlin" "$HERE/llvm-c/src/main/kotlin" -name '*.kt' | sort)
    t0=$(stamp)
    "$MINIC" build $APISRCS -o "$WORK/stage1" --frontend=mini --cflag "-I$LLVM_INC" --cflag "-L$LLVM_LIB" --cflag -rdynamic --cflag -w
    t1=$(stamp)
    echo "stage1 (JVM minic --frontend=mini builds the compiler with the LLVM C API: $(elapsed $t0 $t1))"
    mkdir -p "$WORK/rt"
    for f in $RT; do "$CC" -std=c17 -O1 -w -I "$ROOT/runtime/include" -c "$f" -o "$WORK/rt/$(basename "$f" .c).o"; done
    prev="$WORK/stage1"
    t_prev=$t1
    for stage in 2 3; do
        "$prev" --backend=llvm-api --opt=O1 -o "$WORK/stage$stage.o" --emit-c "$WORK/stage$stage.c" --emit-mir "$WORK/stage$stage.mir" --emit-ll "$WORK/stage$stage.ll" $APISRCS --stdlib $STD
        ta=$(stamp)
        "$CC" "$WORK/stage$stage.o" "$WORK/rt"/*.o -o "$WORK/stage$stage" -rdynamic -L"$LLVM_LIB" -lLLVM-18 -lm
        tb=$(stamp)
        echo "stage$stage (stage$((stage - 1)) compiles the sources and builds the object through the API: $(elapsed $t_prev $ta); system link: $(elapsed $ta $tb))"
        prev="$WORK/stage$stage"
        t_prev=$tb
    done
    cmp "$WORK/stage2.c" "$WORK/stage3.c"
    cmp "$WORK/stage2.mir" "$WORK/stage3.mir"
    cmp "$WORK/stage2.ll" "$WORK/stage3.ll"
    cmp "$WORK/stage2.o" "$WORK/stage3.o"
    echo "stage2 == stage3 (C, MIR, LLVM module text and object file byte-identical)"
    # the plain compiler (no LLVM binding) built through the API by the API compiler: its C and MIR are the C-built chain's
    "$WORK/stage3" --backend=llvm-api --opt=O1 -o "$WORK/plain.o" --emit-c "$WORK/plain.c" --emit-mir "$WORK/plain.mir" $SRCS --stdlib $STD
    "$CC" "$WORK/plain.o" "$WORK/rt"/*.o -o "$WORK/plain" -lm
    "$WORK/plain" -o "$WORK/plain2.c" --emit-mir "$WORK/plain2.mir" $SRCS --stdlib $STD
    cmp "$WORK/plain.c" "$WORK/plain2.c"
    cmp "$WORK/plain.mir" "$WORK/plain2.mir"
    if [ -n "$REFERENCE" ]; then
        cmp "$WORK/plain.c" "$REFERENCE/stage2.c"
        cmp "$WORK/plain.mir" "$REFERENCE/stage2.mir"
        echo "the plain compiler built through the API compiles to the same C and MIR as the C-built compiler in $REFERENCE"
    fi
    echo "bootstrap via the LLVM C API OK: stage2 == stage3, work dir $WORK"
    exit 0
fi

if [ "$BACKEND" = llvm ]; then
    t0=$(stamp)
    "$MINIC" build $SRCS -o "$WORK/stage1" --frontend=mini --backend=llvm --emit-ll "$WORK/stage1.ll"
    t1=$(stamp)
    echo "stage1 (JVM minic --frontend=mini --backend=llvm builds the compiler): $(elapsed $t0 $t1)"
    # libminirt once; the compiler itself is built from the IR
    mkdir -p "$WORK/rt"
    for f in $RT; do "$CC" -std=c17 -O1 -w -I "$ROOT/runtime/include" -c "$f" -o "$WORK/rt/$(basename "$f" .c).o"; done
    prev="$WORK/stage1"
    t_prev=$t1
    for stage in 2 3; do
        "$prev" -o "$WORK/stage$stage.c" --emit-mir "$WORK/stage$stage.mir" --emit-ll "$WORK/stage$stage.ll" $SRCS --stdlib $STD
        ta=$(stamp)
        "$CC" -O1 -Wno-override-module "$WORK/stage$stage.ll" "$WORK/rt"/*.o -o "$WORK/stage$stage" -lm
        tb=$(stamp)
        echo "stage$stage (stage$((stage - 1)) compiles the sources: $(elapsed $t_prev $ta); clang on the .ll: $(elapsed $ta $tb))"
        prev="$WORK/stage$stage"
        t_prev=$tb
    done
    cmp "$WORK/stage2.c" "$WORK/stage3.c"
    cmp "$WORK/stage2.mir" "$WORK/stage3.mir"
    cmp "$WORK/stage2.ll" "$WORK/stage3.ll"
    if [ -n "$REFERENCE" ]; then
        cmp "$WORK/stage2.c" "$REFERENCE/stage2.c"
        cmp "$WORK/stage2.mir" "$REFERENCE/stage2.mir"
        echo "same C and MIR as the C-built compiler in $REFERENCE"
    fi
    echo "bootstrap via LLVM OK: stage2 == stage3 (C, MIR and LLVM IR byte-identical), work dir $WORK"
    exit 0
fi

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

# the native compiler runs the memory checker too: `--check` rejects a leak / use-after-free and accepts a clean program
MEMDIR="$HERE/testdata/memory"
if "$WORK/stage3" --check "$MEMDIR/uaf_simple.kt" --stdlib $STD > "$WORK/check-bad.txt" 2>/dev/null; then
    echo "native --check accepted memory/uaf_simple.kt" >&2; exit 1
fi
grep -q "M0101" "$WORK/check-bad.txt"
"$WORK/stage3" --check "$MEMDIR/ok_heap_basic.kt" --stdlib $STD
echo "native memory check OK"
