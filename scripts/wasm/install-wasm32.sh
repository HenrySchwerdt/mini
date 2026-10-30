#!/usr/bin/env bash
# Stages what `mini build` needs for WebAssembly libraries (kind = "wasm-lib", docs: website/guide/wasm-library.md) next to the toolchain:
#   <root>/lib/wasm32/libminirt.a   the runtime for wasm32-wasip1 (scripts/wasm/build-runtime.sh)
#   <root>/lib/wasm32/sysroot/      wasi-libc, crt1-reactor.o and compiler-rt, taken from the pinned @yowasp/clang package
# The host needs clang with the wasm32 target and wasm-ld (`apt install clang lld`); MINI_WASM_CLANG / MINI_WASM_LD override them.
#   scripts/wasm/install-wasm32.sh [root]        (default <repo>/build/native, the output of ./mini / scripts/build-mini.sh)
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo"
dest="${1:-$repo/build/native}/lib/wasm32"
[ -d scripts/wasm/node_modules ] || npm ci --prefix scripts/wasm
scripts/wasm/build-runtime.sh build/wasm >/dev/null
mkdir -p "$dest/sysroot"
cp build/wasm/libminirt-wasm32.a "$dest/libminirt.a"
if [ ! -f "$dest/sysroot/lib/wasm32-wasip1/crt1-reactor.o" ]; then
    tar xf scripts/wasm/node_modules/@yowasp/clang/gen/llvm-resources.tar -C "$dest/sysroot" lib
fi
echo "wasm32 support staged in $dest"
