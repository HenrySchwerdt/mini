#!/usr/bin/env bash
# Kotlin "box" conformance harness (docs/conformance.md).
#   tools/conformance/run.sh [--filter SUBSTR] [--limit N] [--jobs N] [--tag NAME] ...   (extra args go to run.py)
# Fetches (sparse, shallow) the official tests into ~/.cache/kotlin-box (outside the repo), pinned to KOTLIN_TAG below,
# then runs run.py with the toolchain of this checkout (build/native/bin/minic; build it with ./mini --version).
set -euo pipefail
KOTLIN_TAG="${KOTLIN_TAG:-v2.0.21}"          # JetBrains/kotlin tag, commit 284e9b4041bd815b5b1b489070bbb1f1db6de35c
KOTLIN_COMMIT="284e9b4041bd815b5b1b489070bbb1f1db6de35c"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CACHE="${KOTLIN_BOX_CACHE:-$HOME/.cache/kotlin-box}"
SRC="$CACHE/src"
if [ ! -d "$SRC/.git" ]; then
    mkdir -p "$CACHE"
    git clone --depth 1 --filter=blob:none --sparse --branch "$KOTLIN_TAG" https://github.com/JetBrains/kotlin.git "$SRC"
    (cd "$SRC" && git sparse-checkout set --no-cone compiler/testData/codegen/box compiler/testData/codegen/boxInline)
fi
have="$(cd "$SRC" && git rev-parse HEAD)"
[ "$have" = "$KOTLIN_COMMIT" ] || echo "warning: kotlin checkout is $have, expected $KOTLIN_COMMIT" >&2
MINIC="${MINIC:-$ROOT/build/native/bin/minic}"
[ -x "$MINIC" ] || { echo "no compiler at $MINIC (run ./mini --version first)" >&2; exit 3; }
exec python3 -I "$HERE/run.py" --minic "$MINIC" --tests "$SRC/compiler/testData/codegen" "$@"
