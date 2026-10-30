#!/usr/bin/env bash
# End to end for WebAssembly libraries (docs: website/guide/wasm-library.md): `mini build` turns scripts/wasm/wasm-lib/app (kind = "wasm-lib",
# with a path dependency on a library) into build/wasm/mathlib.{wasm,mjs,d.ts}; test.mjs calls it from Node, test-browser.mjs loads it in
# Chromium through Playwright (skipped when Playwright / Chromium are missing, unless WASM_LIB_BROWSER=1).
#   scripts/wasm/test-wasm-lib.sh          (run from anywhere; needs `./mini` built, scripts/wasm/install-wasm32.sh done, clang + wasm-ld)
#   env: MINI_ROOT (default <repo>/build/native), MINI_WASM_CLANG, MINI_WASM_LD, WASM_LIB_BROWSER
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
export MINI_ROOT="${MINI_ROOT:-$root/build/native}"
work="$root/build/wasm-lib-test"
rm -rf "$work"; mkdir -p "$work"
cp -r "$root/scripts/wasm/wasm-lib/app" "$root/scripts/wasm/wasm-lib/textutil" "$work/"
cd "$work/app"
"$MINI_ROOT/bin/mini" build
"$MINI_ROOT/bin/mini" build 2>&1 | grep -q "no changes" || { echo "second build should be fresh"; exit 1; }
out="$work/app/build/wasm"
ls -l "$out/mathlib.wasm" "$out/mathlib.mjs" "$out/mathlib.d.ts"
node --no-warnings "$root/scripts/wasm/wasm-lib/test.mjs" "$out"
rc=0
node "$root/scripts/wasm/wasm-lib/test-browser.mjs" "$out" || rc=$?
if [ "$rc" = 2 ] && [ "${WASM_LIB_BROWSER:-0}" != 1 ]; then rc=0; fi
exit "$rc"
