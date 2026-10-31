#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
MINIC_API="${MINIC_API:-$ROOT/.build/repl-verified/stage3}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mini-session-api.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
SOURCES=()
STDLIB=()
while IFS= read -r source; do SOURCES+=("$source"); done < <(find "$ROOT/compiler/minic-native/src" -name '*.kt' ! -name Main.kt -print | LC_ALL=C sort)
for module in lower frontend backend llvm-c; do
    while IFS= read -r source; do SOURCES+=("$source"); done < <(find "$ROOT/compiler/$module/src" -name '*.kt' ! -name MainApi.kt -print | LC_ALL=C sort)
done
while IFS= read -r source; do STDLIB+=("$source"); done < <(find "$ROOT/stdlib" -name '*.kt' -print | LC_ALL=C sort)

CC="${CC:-cc}"
LLVM_LIB="$(llvm-config --libdir)"
RUNTIME_OBJECTS=()
mkdir -p "$WORK/runtime"
for source in "$ROOT"/runtime/src/*.c; do
    object="$WORK/runtime/$(basename "${source%.c}").o"
    "$CC" -std=c17 -O1 -w -I "$ROOT/runtime/include" -c "$source" -o "$object"
    RUNTIME_OBJECTS+=("$object")
done

# Keep the API harness out of an outer JIT. It constructs its own ORC sessions, which are the behavior under test.
echo "Building ORC session API smoke executable..."
SECONDS=0
"$MINIC_API" --backend=llvm-api -o "$WORK/session-api-smoke.o" "${SOURCES[@]}" "$ROOT/compiler/llvm-c/tests/LlvmJitSessionSmoke.kt" --stdlib "${STDLIB[@]}"
if [ "$(uname -s)" = Darwin ]; then
    "$CC" -Wl,-export_dynamic -o "$WORK/session-api-smoke" "$WORK/session-api-smoke.o" "${RUNTIME_OBJECTS[@]}" -L"$LLVM_LIB" -lLLVM-18 -lm
else
    "$CC" -rdynamic -o "$WORK/session-api-smoke" "$WORK/session-api-smoke.o" "${RUNTIME_OBJECTS[@]}" -L"$LLVM_LIB" -lLLVM-18 -lm
fi
echo "Built ORC session API smoke executable in ${SECONDS}s; running it..."
SECONDS=0
"$WORK/session-api-smoke"
echo "ORC session API smoke executable passed in ${SECONDS}s"
echo "ORC session API smoke OK"
