#!/usr/bin/env bash
# CI: builds the whole native toolchain of this checkout into build/native (bin/minic-native, bin/mini, lib/libminirt.a, ...)
# starting from the latest release's compiler. No JVM. The test shards run this first (about 2 minutes).
#
#   scripts/ci-toolchain.sh [work-dir]      needs GH_TOKEN and GITHUB_REPOSITORY (or MINI_STAGE0)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:-${RUNNER_TEMP:-/tmp}/stage0}"
stage0="$("$ROOT/scripts/fetch-stage0.sh" "$WORK")"
"$ROOT/scripts/build-mini.sh" --stage0 "$stage0"
"$ROOT/build/native/bin/mini" --version
