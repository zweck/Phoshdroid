#!/bin/bash
# Builds a minimal Termux bootstrap prefix for Phoshdroid.
# Output: app/src/main/assets/bootstrap.tar.zst
#
# Prerequisites:
#   - curl, tar, zstd
#
# Usage: ./rootfs/build-bootstrap.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT="$PROJECT_DIR/app/src/main/assets/bootstrap.tar.zst"
ARCH="aarch64"

STAGING_DIR=$(mktemp -d)
trap "rm -rf $STAGING_DIR" EXIT

echo "=== Phoshdroid bootstrap builder ==="

# Download official Termux bootstrap
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/latest/download/bootstrap-${ARCH}.zip"
echo "Downloading Termux bootstrap..."
curl -L "$BOOTSTRAP_URL" -o "$STAGING_DIR/bootstrap.zip"

echo "Extracting..."
cd "$STAGING_DIR"
unzip -q bootstrap.zip -d prefix

# The bootstrap has symlinks stored as files — fix them
while read -r line; do
    dest=$(echo "$line" | cut -d'←' -f1)
    src=$(echo "$line" | cut -d'←' -f2)
    rm -f "prefix/$dest"
    ln -s "$src" "prefix/$dest"
done < prefix/SYMLINKS.txt
rm -f prefix/SYMLINKS.txt

# Verify essential binaries exist
echo "Verifying bootstrap contents..."
for required in bin/bash bin/proot bin/proot-distro bin/tar usr/bin/zstd; do
    if [ ! -f "prefix/$required" ] && [ ! -L "prefix/$required" ]; then
        echo "WARNING: $required not found in bootstrap, may need manual install"
    fi
done

echo "Compressing..."
tar -cf - -C prefix . | zstd -19 -T0 -o "$OUTPUT"

echo "=== Done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1)) ==="
