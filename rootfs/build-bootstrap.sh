#!/bin/bash
# Builds the Termux bootstrap for Phoshdroid.
#
# Outputs:
#   - app/src/main/jniLibs/arm64-v8a/libbash.so       (executable via nativeLibDir)
#   - app/src/main/jniLibs/arm64-v8a/libproot.so       (executable via nativeLibDir)
#   - app/src/main/assets/bootstrap.tar.gz              (scripts + libs, non-executable)
#
# Native binaries go into jniLibs/ so Android extracts them with exec permission.
# Everything else (scripts, shared libs, config) goes into the assets tarball.
#
# Prerequisites: curl, tar, unzip
# Usage: ./rootfs/build-bootstrap.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
ASSET_OUTPUT="$PROJECT_DIR/app/src/main/assets/bootstrap.tar.gz"
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

# Fix symlinks (Termux stores them in SYMLINKS.txt)
# Format: target←link_path (link_path points to target)
if [ -f prefix/SYMLINKS.txt ]; then
    while IFS='←' read -r target linkpath; do
        # linkpath is relative to prefix (e.g., ./lib/libfoo.so)
        rm -f "prefix/$linkpath"
        mkdir -p "$(dirname "prefix/$linkpath")"
        ln -sf "$target" "prefix/$linkpath"
    done < prefix/SYMLINKS.txt
    rm -f prefix/SYMLINKS.txt
fi

# Verify essential files exist
echo "Verifying bootstrap contents..."
MISSING=0
for required in bin/bash bin/proot bin/proot-distro; do
    if [ ! -f "prefix/$required" ] && [ ! -L "prefix/$required" ]; then
        echo "ERROR: $required not found in bootstrap"
        MISSING=1
    fi
done

if [ "$MISSING" -eq 1 ]; then
    echo ""
    echo "The Termux bootstrap may not include proot/proot-distro by default."
    echo "You may need to install them into the prefix manually:"
    echo "  1. Install Termux on an Android device"
    echo "  2. Run: pkg install proot proot-distro"
    echo "  3. Copy the prefix from the device"
    echo ""
    echo "Continuing anyway — binaries may need to be added manually."
fi

# === Extract native binaries into jniLibs (Android makes these executable) ===
mkdir -p "$JNILIBS_DIR"

# Resolve symlinks and copy actual binaries
for bin_name in bash proot; do
    src="prefix/bin/$bin_name"
    if [ -L "$src" ]; then
        src=$(readlink -f "$src")
    fi
    if [ -f "$src" ]; then
        cp "$src" "$JNILIBS_DIR/lib${bin_name}.so"
        echo "  Extracted $bin_name -> jniLibs/arm64-v8a/lib${bin_name}.so ($(du -h "$JNILIBS_DIR/lib${bin_name}.so" | cut -f1))"
    else
        echo "  WARNING: $bin_name binary not found, skipping"
    fi
done

# === Package everything else as an asset tarball (scripts, libs, config) ===
# Remove the binaries we already extracted to jniLibs to avoid duplication
rm -f prefix/bin/bash prefix/bin/proot 2>/dev/null || true

echo "Compressing bootstrap prefix..."
tar -czf "$ASSET_OUTPUT" -C prefix .

echo ""
echo "=== Done ==="
echo "  Native libs: $JNILIBS_DIR/"
ls -lh "$JNILIBS_DIR/"*.so 2>/dev/null || echo "  (none)"
echo "  Asset tarball: $ASSET_OUTPUT ($(du -h "$ASSET_OUTPUT" | cut -f1))"
echo ""
echo "Now rebuild the APK: ./gradlew assembleDebug"
