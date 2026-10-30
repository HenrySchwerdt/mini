#!/bin/sh
# Installs a prebuilt Mini (minic) from GitHub Releases into ~/.mini.
#
#   curl -fsSL https://raw.githubusercontent.com/HenrySchwerdt/mini/main/install.sh | sh
#
# Environment:
#   MINI_VERSION   version to install, e.g. 0.4.0 or v0.4.0 (default: the latest release)
#   MINI_HOME      install directory (default: ~/.mini)
#   MINI_TARBALL   install from a local mini-<version>-<target>.tar.gz instead of downloading (testing);
#                  checked against a SHA256SUMS next to it when there is one
set -eu

REPO="HenrySchwerdt/mini"
MINI_HOME="${MINI_HOME:-$HOME/.mini}"

say() { printf '%s\n' "$*"; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

# --- platform
os="$(uname -s)"
arch="$(uname -m)"
case "$os/$arch" in
    Linux/x86_64|Linux/amd64)   target=linux-x64 ;;
    Linux/aarch64|Linux/arm64)  target=linux-arm64 ;;
    Darwin/arm64)               target=macos-arm64 ;;
    *) die "no prebuilt Mini for $os/$arch (available: linux-x64, linux-arm64, macos-arm64); build from source, see the README" ;;
esac

command -v tar >/dev/null 2>&1 || die "tar is required"
if command -v sha256sum >/dev/null 2>&1; then
    sha256() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
    sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
    die "sha256sum or shasum is required"
fi

tmp="$(mktemp -d "${TMPDIR:-/tmp}/mini-install.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT INT TERM

fetch() { # url dest
    if command -v curl >/dev/null 2>&1; then curl -fsSL "$1" -o "$2"
    elif command -v wget >/dev/null 2>&1; then wget -q "$1" -O "$2"
    else die "curl or wget is required"; fi
}

if [ -n "${MINI_TARBALL:-}" ]; then
    [ -f "$MINI_TARBALL" ] || die "MINI_TARBALL: no such file $MINI_TARBALL"
    tarball="$MINI_TARBALL"
    sums="$(dirname "$MINI_TARBALL")/SHA256SUMS"
    [ -f "$sums" ] || sums=""
    asset="$(basename "$MINI_TARBALL")"
else
    version="${MINI_VERSION:-}"
    if [ -z "$version" ]; then
        command -v curl >/dev/null 2>&1 || die "MINI_VERSION is required without curl"
        # /releases/latest redirects to /releases/tag/vX.Y.Z (no API call, no rate limit)
        url="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest")" \
            || die "cannot reach GitHub; set MINI_VERSION or MINI_TARBALL"
        version="${url##*/}"
        case "$version" in v[0-9]*) ;; *) die "no release found at https://github.com/$REPO/releases (set MINI_VERSION once there is one)" ;; esac
    fi
    version="${version#v}"
    asset="mini-$version-$target.tar.gz"
    base="https://github.com/$REPO/releases/download/v$version"
    say "Downloading Mini $version for $target"
    tarball="$tmp/$asset"
    fetch "$base/$asset" "$tarball" || die "download failed: $base/$asset"
    sums="$tmp/SHA256SUMS"
    fetch "$base/SHA256SUMS" "$sums" || die "download failed: $base/SHA256SUMS"
fi

# --- verify
if [ -n "$sums" ]; then
    want="$(awk -v f="$asset" '$2 == f || $2 == "*" f { print $1 }' "$sums")"
    [ -n "$want" ] || die "$asset is not listed in SHA256SUMS"
    got="$(sha256 "$tarball")"
    [ "$want" = "$got" ] || die "SHA256 mismatch for $asset (expected $want, got $got)"
    say "SHA256 verified"
else
    say "warning: no SHA256SUMS next to MINI_TARBALL, checksum not verified"
fi

# --- install
mkdir -p "$tmp/x"
tar -xzf "$tarball" -C "$tmp/x"
src="$(find "$tmp/x" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
[ -x "$src/bin/minic" ] || die "unexpected archive layout"
mkdir -p "$MINI_HOME"
for d in bin lib include stdlib; do rm -rf "${MINI_HOME:?}/$d"; done
for f in "$src"/*; do
    rm -rf "${MINI_HOME:?}/$(basename "$f")"
    mv "$f" "$MINI_HOME/"
done
say "Installed Mini $(cat "$MINI_HOME/VERSION" 2>/dev/null || echo '?') to $MINI_HOME"

case ":$PATH:" in
    *":$MINI_HOME/bin:"*) ;;
    *)
        say ""
        say "Add minic to your PATH (put it in your shell profile):"
        say "    export PATH=\"$MINI_HOME/bin:\$PATH\""
        ;;
esac
say ""
say "minic needs a C compiler (clang or gcc). Try:"
say "    minic run hello.kt"
