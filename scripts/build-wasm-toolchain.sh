#!/usr/bin/env bash
# Builds the browser toolchain (docs/plan-kotlin.md 10i): everything the playground loads, in build/wasm-toolchain/
# and as build/wasm-toolchain.tar.gz.
#
#   minic.wasm              the compiler (frontend, memory checker, lowering, LLVM IR emitter) as a wasm32-wasip1 command
#   libminirt-wasm32.a      the runtime for wasm32-wasip1
#   minirt-entry-wasm32.o   the C `main` that calls mk_main (linked next to the program)
#   stdlib.json             {"files": [{"path", "text"}...]} the stdlib sources, in compilation order
#   libs.json               {"libs": [{"name", "packages", "files": [{"path", "text"}]}]} the preinstalled libraries (libs/*/src)
#   toolchain.json          versions, sizes and the command lines the browser runs
#   build/site/toolchain/   the same, laid out for website/public/toolchain (manifest.json, <version>/mini-toolchain.worker.js, wasm files, @yowasp/clang modules)
#
# clang and wasm-ld (with wasi-libc and compiler-rt) are NOT part of the bundle: they come from the pinned npm
# package @yowasp/clang (scripts/wasm/package.json), which the playground loads as `commands.clang` (see compiler/README.md).
#
#   scripts/build-wasm-toolchain.sh [--skip-test]
# Needs node 22 and the native compiler (build/native/bin/minic-native; `./mini` builds it, done here when missing).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
skip_test=0; [ "${1:-}" = "--skip-test" ] && skip_test=1
w=build/wasm
dist=build/wasm-toolchain

[ -x build/native/bin/minic-native ] || ./mini --version >/dev/null
[ -d scripts/wasm/node_modules ] || npm ci --prefix scripts/wasm
scripts/build-mini.sh --quiet

scripts/wasm/build-runtime.sh "$w"
scripts/wasm/build-minic-wasm.sh "$w"

if [ "$skip_test" -eq 0 ]; then
    scripts/wasm/test-programs.sh
    scripts/wasm/test-minic-wasm.sh "$w"
fi

rm -rf "$dist"; mkdir -p "$dist"
cp "$w/minic.wasm" "$w/libminirt-wasm32.a" "$w/minirt-entry-wasm32.o" "$dist/"
cp build/native/lib/stdlib.mlib "$dist/stdlib.mlib"
node scripts/wasm/pack-mlibs.mjs . "$dist/libs.json" build/native/bin/minic-native build/native/lib/stdlib.mlib
clang_version="$(node -p "require('./scripts/wasm/package.json').dependencies['@yowasp/clang']")"
node -e '
const fs = require("fs")
const [dist, clangVersion, commit] = process.argv.slice(1)
const size = f => fs.statSync(`${dist}/${f}`).size
const libAssets = JSON.parse(fs.readFileSync(`${dist}/libs.json`, "utf8")).libs.map(l => l.path).filter(Boolean)
fs.writeFileSync(`${dist}/toolchain.json`, JSON.stringify({
  format: 1,
  commit,
  clang: { package: "@yowasp/clang", version: clangVersion, target: "wasm32-wasip1" },
  files: Object.fromEntries(["minic.wasm", "libminirt-wasm32.a", "minirt-entry-wasm32.o", "stdlib.mlib", "libs.json", ...libAssets].map(f => [f, size(f)])),
  // what the browser runs, in order (all paths relative to the virtual working directory)
  pipeline: {
    check: ["minic.wasm --check --brief prog.kt --lib stdlib.mlib"],
    compile: [
      "minic.wasm --emit-ll prog.ll prog.kt --lib stdlib.mlib --lib <used preinstalled libraries>",
      "clang --target=wasm32-wasip1 -O1 -Wno-override-module -c prog.ll -o prog.o",
      "clang --target=wasm32-wasip1 prog.o libminirt-wasm32.a minirt-entry-wasm32.o -o prog.wasm"
    ],
    run: "prog.wasm as a WASI command"
  }
}, null, 2) + "\n")
' "$dist" "$clang_version" "$(git rev-parse HEAD 2>/dev/null || echo unknown)"
tar -C build -czf build/wasm-toolchain.tar.gz wasm-toolchain
ls -l "$dist" build/wasm-toolchain.tar.gz

# The website layout (manifest.json + versioned directory with the module worker), checked through the real worker protocol.
node scripts/wasm/pack-site.mjs "$dist" build/site/toolchain
[ "$skip_test" -eq 1 ] || node --no-warnings scripts/wasm/test-worker.mjs build/site/toolchain
tar -C build/site -czf build/wasm-toolchain-site.tar.gz toolchain
