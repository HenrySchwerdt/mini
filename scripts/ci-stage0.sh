#!/usr/bin/env bash
# CI: bootstrap with the latest release's native compiler as stage0 (no JVM, no K2), stage2 == stage3 checked.
# Falls back to the JVM stage1 (./gradlew :jvm-bridge:installDist, then bootstrap.sh without --stage0) when there is
# no release for this platform yet, the download or checksum fails, or stage0 cannot build the current sources.
#
#   scripts/ci-stage0.sh <work-dir>           needs GH_TOKEN and GITHUB_REPOSITORY (or MINI_STAGE0=<path to minic-native>)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:?usage: ci-stage0.sh <work-dir>}"
mkdir -p "$WORK"

case "$(uname -s)/$(uname -m)" in
    Linux/x86_64)  target=linux-x64 ;;
    Linux/aarch64) target=linux-arm64 ;;
    Darwin/arm64)  target=macos-arm64 ;;
    *)             target="" ;;
esac

fallback() {
    echo "::warning::stage0: $1; falling back to the JVM stage1"
    (cd "$ROOT/compiler" && ./gradlew :jvm-bridge:installDist --console=plain) && "$ROOT/compiler/bootstrap.sh" "$WORK/jvm"
    exit $?
}

stage0="${MINI_STAGE0:-}"
if [ -z "$stage0" ]; then
    [ -n "$target" ] || fallback "no prebuilt compiler for $(uname -sm)"
    dl="$WORK/release"
    rm -rf "$dl"; mkdir -p "$dl"
    # no --tag: the latest (non-prerelease, non-draft) release
    gh release download --repo "${GITHUB_REPOSITORY:?}" --dir "$dl" --pattern "mini-*-$target.tar.gz" --pattern SHA256SUMS \
        || fallback "no release to download (none published yet, or the download failed)"
    tarball="$(ls "$dl"/mini-*-"$target".tar.gz 2>/dev/null | head -n 1)"
    [ -n "$tarball" ] || fallback "the latest release has no $target tarball"
    want="$(awk -v f="$(basename "$tarball")" '$2 == f { print $1 }' "$dl/SHA256SUMS")"
    if command -v sha256sum >/dev/null 2>&1; then got="$(sha256sum "$tarball" | cut -d' ' -f1)"; else got="$(shasum -a 256 "$tarball" | cut -d' ' -f1)"; fi
    [ -n "$want" ] && [ "$want" = "$got" ] || fallback "SHA256 mismatch for $(basename "$tarball")"
    tar -xzf "$tarball" -C "$dl"
    stage0="$(ls -d "$dl"/mini-*-"$target")/bin/minic-native"
    echo "stage0: $(basename "$tarball")"
fi

if "$ROOT/compiler/bootstrap.sh" --stage0 "$stage0" "$WORK/s0"; then
    exit 0
fi
fallback "the stage0 bootstrap failed (a real regression fails the JVM path too)"
