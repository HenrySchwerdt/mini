#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
MINIC_API="${MINIC_API:-$ROOT/.build/repl-verified/stage3}"
SOURCES=()
STDLIB=()
while IFS= read -r source; do SOURCES+=("$source"); done < <(find "$ROOT/compiler/minic-native/src" -name '*.kt' ! -name Main.kt -print | LC_ALL=C sort)
for module in lower frontend backend llvm-c; do
    while IFS= read -r source; do SOURCES+=("$source"); done < <(find "$ROOT/compiler/$module/src" -name '*.kt' ! -name MainApi.kt -print | LC_ALL=C sort)
done
while IFS= read -r source; do STDLIB+=("$source"); done < <(find "$ROOT/stdlib" -name '*.kt' -print | LC_ALL=C sort)

"$MINIC_API" --backend=llvm-api --run "${SOURCES[@]}" "$ROOT/compiler/llvm-c/tests/LlvmJitSessionSmoke.kt" --stdlib "${STDLIB[@]}"
echo "ORC session API smoke OK"
