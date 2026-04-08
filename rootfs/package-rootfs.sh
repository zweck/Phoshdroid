#!/bin/bash
# Package the pmbootstrap rootfs for Phoshdroid APK.
# Requires sudo (rootfs has root-owned files).
# Usage: ./rootfs/package-rootfs.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT="$PROJECT_DIR/app/src/main/assets/rootfs.tar"
OVERLAY_DIR="$SCRIPT_DIR/overlay"
ROOTFS="/home/phil/.local/var/pmbootstrap/chroot_rootfs_qemu-aarch64"

echo "=== Packaging pmOS rootfs for Phoshdroid ==="

if [ ! -d "$ROOTFS" ]; then
    echo "ERROR: Rootfs not found at $ROOTFS"
    echo "Run 'pmbootstrap install' first."
    exit 1
fi

STAGING_DIR=$(mktemp -d)
trap "sudo rm -rf $STAGING_DIR" EXIT

echo "Copying rootfs (excluding kernel/firmware)..."
sudo tar -cf - \
    --exclude='./boot' \
    --exclude='./lib/modules' \
    --exclude='./lib/firmware' \
    --exclude='./dev/*' \
    --exclude='./proc/*' \
    --exclude='./sys/*' \
    --exclude='./tmp/*' \
    -C "$ROOTFS" . | sudo tar -xf - -C "$STAGING_DIR"

echo "Applying Phoshdroid overlay..."
sudo cp -r "$OVERLAY_DIR/"* "$STAGING_DIR/"
sudo chmod +x "$STAGING_DIR/etc/phoshdroid/start.sh"

echo "Creating tarball (uncompressed — APK handles compression)..."
sudo tar -cf "$OUTPUT" -C "$STAGING_DIR" .
sudo chown "$(whoami)" "$OUTPUT"

SIZE=$(du -h "$OUTPUT" | cut -f1)
echo ""
echo "=== Done: $OUTPUT ($SIZE) ==="
echo "Now rebuild: ./gradlew assembleDebug"
