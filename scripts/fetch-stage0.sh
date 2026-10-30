#!/usr/bin/env bash
# Downloads the native compiler of the latest release (once) and prints the path of its bin/minic-native on stdout.
# This is the only thing a clean checkout needs besides a C compiler: everything else is built from the sources with it.
#
#   scripts/fetch-stage0.sh <dir>        needs GH_TOKEN and GITHUB_REPOSITORY (CI) for `gh`, else curl against the public repo
#   MINI_STAGE0=<path to minic-native>   skips the download and prints that path
set -euo pipefail
DIR="${1:?usage: fetch-stage0.sh <dir>}"
if [ -n "${MINI_STAGE0:-}" ]; then echo "$MINI_STAGE0"; exit 0; fi
case "$(uname -s)/$(uname -m)" in
    Linux/x86_64)  target=linux-x64 ;;
    Linux/aarch64) target=linux-arm64 ;;
    Darwin/arm64)  target=macos-arm64 ;;
    *) echo "fetch-stage0.sh: no prebuilt compiler for $(uname -sm); set MINI_STAGE0 to a native minic-native" >&2; exit 1 ;;
esac
repo="${GITHUB_REPOSITORY:-HenrySchwerdt/mini}"
mkdir -p "$DIR"
if ls "$DIR"/mini-*-"$target"/bin/minic-native >/dev/null 2>&1; then
    ls -d "$DIR"/mini-*-"$target" | head -n 1 | sed 's|$|/bin/minic-native|'
    exit 0
fi
rm -rf "$DIR/dl"; mkdir -p "$DIR/dl"
if command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}" ]; then
    # no --tag: the latest (non-prerelease, non-draft) release
    gh release download --repo "$repo" --dir "$DIR/dl" --pattern "mini-*-$target.tar.gz" --pattern SHA256SUMS >&2
else
    api="https://api.github.com/repos/$repo/releases/latest"
    urls="$(curl -fsSL "$api" | grep -o '"browser_download_url": *"[^"]*"' | sed 's/.*"\(http[^"]*\)"/\1/')"
    for u in $(echo "$urls" | grep -E "/(mini-[^/]*-$target\.tar\.gz|SHA256SUMS)$"); do
        curl -fsSL -o "$DIR/dl/$(basename "$u")" "$u"
    done
fi
tarball="$(ls "$DIR"/dl/mini-*-"$target".tar.gz 2>/dev/null | head -n 1)"
[ -n "$tarball" ] || { echo "fetch-stage0.sh: the latest release has no $target tarball" >&2; exit 1; }
want="$(awk -v f="$(basename "$tarball")" '$2 == f { print $1 }' "$DIR/dl/SHA256SUMS")"
if command -v sha256sum >/dev/null 2>&1; then got="$(sha256sum "$tarball" | cut -d' ' -f1)"; else got="$(shasum -a 256 "$tarball" | cut -d' ' -f1)"; fi
[ -n "$want" ] && [ "$want" = "$got" ] || { echo "fetch-stage0.sh: SHA256 mismatch for $(basename "$tarball")" >&2; exit 1; }
tar -xzf "$tarball" -C "$DIR"
echo "stage0: $(basename "$tarball")" >&2
ls -d "$DIR"/mini-*-"$target" | head -n 1 | sed 's|$|/bin/minic-native|'
