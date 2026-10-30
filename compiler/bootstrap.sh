#!/bin/bash
# B3 bootstrap: the compiler (frontend, lower, backend, minic-native driver) builds itself, natively (no JVM, no Gradle).
#
#   stage0 = a native compiler given from outside (a release's bin/minic-native, or the C bootstrap seed)
#   stage1 = stage0 compiles the compiler sources to C, the C compiler builds it
#   stage2 = stage1 compiles the same sources to C, the C compiler builds it
#   stage3 = stage2 compiles the same sources to C, the C compiler builds it
#
# Check: the C and MIR written by stage2 and stage3 are byte-identical (stage0 may be an older compiler version, so stage1 is
# not compared: stage2 == stage3 is the fixpoint). Wall times per stage are printed. Needs a C compiler (clang by default).
#
# Usage: compiler/bootstrap.sh [--stage0 <native minic>] [--backend=llvm|llvm-api [--reference <dir>]] [work-dir]
#
# --stage0 defaults to $MINI_STAGE0, then build/native/bin/minic-native (scripts/build-mini.sh), then ~/.mini/bin/minic-native.
# Environment: CC (default clang), BOOT_OPT (default -O0, the optimization level of the built stages: nothing is benchmarked here,
# clang -O0 takes 9 s instead of 73 s per stage).
#
# --backend=llvm (docs/plan-kotlin.md 10g): every stage is built through the LLVM backend (MIR -> textual LLVM IR ->
# clang) instead of C: every stage is built from the .ll file written by the previous one (`--emit-ll`). Check: C, MIR and LLVM IR of stage2 and stage3 are byte-identical; with
# --reference <dir> (the work dir of a C bootstrap) the C and MIR of stage2 must also equal the C-built compiler's.
#
# --backend=llvm-api (docs/plan-kotlin.md 10g phase 2): the compiler that has the LLVM C API bound in (compiler/llvm-c, needs the LLVM 18
# headers and libLLVM: `llvm-config`) builds itself with no text IR and no clang for the code: every stage compiles the sources to MIR,
# builds the LLVM module in memory and writes the object file through the target machine (`--backend=llvm-api -o x.o`); only the final link
# runs the system C compiler. stage1 = stage0 compiles the API compiler to C (llvm-c sources, mini.llvmapi.main instead of Main.kt); stage2 and
# stage3 are built by the previous stage. Check: C, MIR, the LLVM module text and the object file of stage2 and stage3 are byte-identical.
# Then stage3 builds the plain compiler (no llvm-c) through the API too; with --reference <dir> its C and MIR must equal the C-built chain's.
set -e
STAGE0="${MINI_STAGE0:-}"
if [ "$1" = "--stage0" ]; then
    if [ -z "$2" ] || [ ! -x "$2" ]; then echo "bootstrap.sh: --stage0 needs an executable native minic" >&2; exit 2; fi
    STAGE0="$2"
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
CC="${CC:-clang}"
OPT="${BOOT_OPT:--O0}"
if [ -z "$STAGE0" ]; then
    for cand in "$ROOT/build/native/bin/minic-native" "$HOME/.mini/bin/minic-native"; do
        [ -x "$cand" ] && { STAGE0="$cand"; break; }
    done
fi
if [ -z "$STAGE0" ] || [ ! -x "$STAGE0" ]; then
    echo "bootstrap.sh: no native compiler to start from: pass --stage0 <minic-native>, set MINI_STAGE0, or run ./mini once (scripts/build-mini.sh)" >&2
    exit 2
fi
STAGE0="$(cd "$(dirname "$STAGE0")" && pwd)/$(basename "$STAGE0")"
mkdir -p "$WORK"

SRCS=$(find "$HERE/minic-native/src" "$HERE/lower/src" "$HERE/frontend/src" "$HERE/backend/src" -name '*.kt' | sort)
STD=$(find "$ROOT/stdlib" -name '*.kt' | sort)
RT=$(ls "$ROOT"/runtime/src/*.c)

stamp() { date +%s.%N; }
elapsed() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f s", b - a }'; }

if [ "$BACKEND" = llvm-api ]; then
    LLVM_INC="${LLVM_INC:-$(llvm-config --includedir)}"
    LLVM_LIB="${LLVM_LIB:-$(llvm-config --libdir)}"
    APISRCS=$(find "$HERE/minic-native/src" -name '*.kt' ! -name Main.kt | sort; find "$HERE/lower/src" "$HERE/frontend/src" "$HERE/backend/src" "$HERE/llvm-c/src" -name '*.kt' | sort)
    t0=$(stamp)
    "$STAGE0" -o "$WORK/stage1.c" $APISRCS --stdlib $STD
    mkdir -p "$WORK/rt"
    for f in $RT; do "$CC" -std=c17 -O1 -w -I "$ROOT/runtime/include" -c "$f" -o "$WORK/rt/$(basename "$f" .c).o"; done
    "$CC" -std=c17 $OPT -w -I "$ROOT/runtime/include" -I"$LLVM_INC" "$WORK/stage1.c" "$WORK/rt"/*.o -o "$WORK/stage1" -L"$LLVM_LIB" -lLLVM-18 -rdynamic -lm
    t1=$(stamp)
    echo "stage1 (native stage0 compiles the compiler with the LLVM C API to C, no JVM: $(elapsed $t0 $t1))"
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
    # libminirt once; the compiler itself is built from the IR
    mkdir -p "$WORK/rt"
    for f in $RT; do "$CC" -std=c17 -O1 -w -I "$ROOT/runtime/include" -c "$f" -o "$WORK/rt/$(basename "$f" .c).o"; done
    "$STAGE0" -o "$WORK/stage1.c" --emit-mir "$WORK/stage1.mir" --emit-ll "$WORK/stage1.ll" $SRCS --stdlib $STD
    "$CC" -O1 -Wno-override-module "$WORK/stage1.ll" "$WORK/rt"/*.o -o "$WORK/stage1" -lm
    t1=$(stamp)
    echo "stage1 (native stage0 compiles the sources, clang on the .ll, no JVM): $(elapsed $t0 $t1)"
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
"$STAGE0" -o "$WORK/stage1.c" $SRCS --stdlib $STD
t0b=$(stamp)
"$CC" -std=c17 $OPT -w -I "$ROOT/runtime/include" "$WORK/stage1.c" $RT -o "$WORK/stage1" -lm
t1=$(stamp)
echo "stage1 (native stage0 $STAGE0 compiles the sources: $(elapsed $t0 $t0b); clang: $(elapsed $t0b $t1))"

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
if "$WORK/stage3" --check "$MEMDIR/uaf_simple.kt" --stdlib $STD > "$WORK/check-bad.txt" 2>&1; then
    echo "native --check accepted memory/uaf_simple.kt" >&2; exit 1
fi
grep -q "M0101" "$WORK/check-bad.txt"
"$WORK/stage3" --check "$MEMDIR/ok_heap_basic.kt" --stdlib $STD
echo "native memory check OK"
