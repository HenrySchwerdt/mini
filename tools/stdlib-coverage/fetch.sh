#!/usr/bin/env bash
# Unpacks the Kotlin stdlib sources (commonMain/jvmMain) that coverage.py compares against into ~/.cache/kotlin-stdlib-src.
# Source: kotlin-stdlib-sources.jar of the pinned release (Maven Central) unless KOTLIN_STDLIB_SOURCES_JAR points at one
# (e.g. <kotlinc>/lib/kotlin-stdlib-sources.jar of a kotlinc distribution).
set -euo pipefail
VER="${KOTLIN_STDLIB_VERSION:-2.1.20}"
DEST="${KOTLIN_STDLIB_SOURCES:-$HOME/.cache/kotlin-stdlib-src}"
[ -d "$DEST/commonMain" ] && { echo "$DEST"; exit 0; }
JAR="${KOTLIN_STDLIB_SOURCES_JAR:-}"
tmp=""
if [ -z "$JAR" ]; then
    tmp="$(mktemp -d)"; JAR="$tmp/sources.jar"
    curl -fsSL -o "$JAR" "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/$VER/kotlin-stdlib-$VER-sources.jar"
fi
mkdir -p "$DEST"; unzip -qo "$JAR" -d "$DEST"
[ -n "$tmp" ] && rm -rf "$tmp"
echo "$DEST"
