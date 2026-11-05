#!/usr/bin/env bash
# The compiler bootstraps of the validation list: stage2 == stage3 through the C backend and through --backend=llvm-api (needs
# llvm-config 18), started from the C-built compiler of build/native (scripts/build-mini.sh; bench/hillclimb/rebuild.sh c).
#   bench/hillclimb/bootstrap.sh [c|api|both]
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WHICH="${1:-both}"
W="$ROOT/build/hc/bootstrap"
rm -rf "$W"
mkdir -p "$W"
STAGE0="${MINI_STAGE0:-$ROOT/build/native/bin/minic-native}"
rc=0
if [ "$WHICH" = c ] || [ "$WHICH" = both ]; then
    echo "== bootstrap C"
    "$ROOT/compiler/bootstrap.sh" --stage0 "$STAGE0" "$W/c" > "$W/c.log" 2>&1 && echo "   ok" || { echo "   FAILED (see $W/c.log)"; tail -5 "$W/c.log"; rc=1; }
fi
if [ "$WHICH" = api ] || [ "$WHICH" = both ]; then
    echo "== bootstrap llvm-api"
    "$ROOT/compiler/bootstrap.sh" --stage0 "$STAGE0" --backend=llvm-api "$W/api" > "$W/api.log" 2>&1 && echo "   ok" || { echo "   FAILED (see $W/api.log)"; tail -5 "$W/api.log"; rc=1; }
fi
exit $rc
