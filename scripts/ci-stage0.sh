#!/usr/bin/env bash
# CI: bootstrap with the latest release's native compiler as stage0 (no JVM, no K2), stage2 == stage3 checked.
# A failure is a failure: there is no JVM fallback any more.
#
#   scripts/ci-stage0.sh <work-dir> [bootstrap options]   (e.g. --backend=llvm)
#   needs GH_TOKEN and GITHUB_REPOSITORY (or MINI_STAGE0=<path to minic-native>)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:?usage: ci-stage0.sh <work-dir> [bootstrap options]}"
shift
mkdir -p "$WORK"
stage0="$("$ROOT/scripts/fetch-stage0.sh" "$WORK/release")"
exec "$ROOT/compiler/bootstrap.sh" --stage0 "$stage0" "$@" "$WORK/s0"
