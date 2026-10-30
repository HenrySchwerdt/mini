#!/usr/bin/env bash
# The compiler itself as a WebAssembly module: build/wasm/minic.wasm (wasm32-wasip1 command).
#   sources of the compiler (own frontend + memory checker + lowering + LLVM IR emitter + minic-native driver)
#   -> JVM `minic emit-mir` -> native-driver `--backend=llvm --target=wasm32` -> clang -> link with libminirt-wasm32.a
#
# Fast path (seconds): the host's clang with the wasm32 target and wasm-ld; fallback (about 30 min): the prebuilt clang.wasm of
# @yowasp/clang, an assertions build that runs as wasm. The IR is only compiled (`-c`, no headers needed); linking takes wasi-libc,
# crt1 and compiler-rt from the sysroot inside @yowasp/clang's llvm-resources.tar. Both paths give a module that behaves the same
# (test-minic-wasm.sh). Environment: MINIC_WASM_NATIVE=0 forces clang.wasm, MINIC_WASM_NATIVE_CLANG / MINIC_WASM_NATIVE_LD name the tools.
#
# Needs: the JVM drivers (`cd compiler && ./gradlew installDist :native-driver:installDist`), build-runtime.sh done.
#   scripts/wasm/build-minic-wasm.sh [out-dir]   (default build/wasm; run from the repository root, out-dir relative)
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
out="${1:-build/wasm}"
MINIC="${MINIC:-$root/compiler/jvm-bridge/build/install/minic/bin/minic}"
NATIVE_DRIVER="${NATIVE_DRIVER:-$root/compiler/native-driver/build/install/native-driver/bin/native-driver}"
Y="node scripts/wasm/yowasp.mjs"
OPT="${MINIC_WASM_OPT:--O1}"
export JAVA_OPTS="${JAVA_OPTS:--Xss512m -Xmx6g}"
stamp() { date +%s; }

mkdir -p "$out/minic"
SRCS=$(find compiler/minic-native/src/main/kotlin compiler/lower/src/main/kotlin compiler/frontend/src/main/kotlin compiler/backend/src/main/kotlin -name '*.kt' | sort)

NCLANG="${MINIC_WASM_NATIVE_CLANG:-clang}"
NLD="${MINIC_WASM_NATIVE_LD:-wasm-ld}"
native=0
if [ "${MINIC_WASM_NATIVE:-auto}" != 0 ] && command -v "$NCLANG" >/dev/null && command -v "$NLD" >/dev/null \
   && "$NCLANG" --print-targets 2>/dev/null | grep -q wasm32; then native=1; fi

t0=$(stamp)
t1=$t0; t2=$t0; t3=$t0
if { [ "${MINIC_WASM_REUSE:-0}" = 1 ] || [ "${MINIC_WASM_REUSE:-}" = true ]; } && [ -f "$out/minic/minic.o" ]; then
    # restored from the CI cache, whose key covers every input of the object (compiler, stdlib, runtime, toolchain versions)
    echo "reusing $out/minic/minic.o"
else
    "$MINIC" emit-mir $SRCS -o "$out/minic/minic.mir" --frontend=mini
    t1=$(stamp)
    "$NATIVE_DRIVER" "$out/minic/minic.mir" --backend=llvm --target=wasm32 -o "$out/minic/minic.ll"
    t2=$(stamp)
    if [ "$native" = 1 ]; then
        "$NCLANG" --target=wasm32-wasip1 $OPT -Wno-override-module -c "$out/minic/minic.ll" -o "$out/minic/minic.o"
    else
        $Y clang --target=wasm32-wasip1 $OPT -Wno-override-module -c "$out/minic/minic.ll" -o "$out/minic/minic.o"
    fi
    t3=$(stamp)
    rm -f "$out/minic/minic.ll" "$out/minic/minic.mir"
fi
if [ "$native" = 1 ]; then
    sysroot="$out/sysroot"
    [ -d "$sysroot/lib/wasm32-wasip1" ] || { mkdir -p "$sysroot"; tar xf scripts/wasm/node_modules/@yowasp/clang/gen/llvm-resources.tar -C "$sysroot" lib; }
    "$NLD" -m wasm32 -z stack-size=33554432 -L"$sysroot/lib/wasm32-wasip1" "$sysroot/lib/wasm32-wasip1/crt1-command.o" \
        "$out/minic/minic.o" "$out/libminirt-wasm32.a" "$out/minirt-entry-wasm32.o" -lc \
        "$sysroot/lib/wasm32-unknown-wasip1/libclang_rt.builtins.a" -o "$out/minic.wasm"
else
    $Y clang --target=wasm32-wasip1 -Wl,-z,stack-size=33554432 "$out/minic/minic.o" "$out/libminirt-wasm32.a" "$out/minirt-entry-wasm32.o" -o "$out/minic.wasm"
fi
t4=$(stamp)
echo "minic.wasm: $(stat -c %s "$out/minic.wasm") bytes (native clang: $native); emit-mir $((t1 - t0)) s, wasm32 IR $((t2 - t1)) s, clang $((t3 - t2)) s, link $((t4 - t3)) s"
