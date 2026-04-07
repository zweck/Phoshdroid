#!/bin/bash
# Builds a pmOS rootfs for Phoshdroid using pmbootstrap.
# Output: app/src/main/assets/rootfs.tar.zst
#
# Prerequisites:
#   - pmbootstrap installed (pip install pmbootstrap)
#   - ~2GB disk space for build
#
# Usage: ./rootfs/build-rootfs.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT="$PROJECT_DIR/app/src/main/assets/rootfs.tar.zst"
OVERLAY_DIR="$SCRIPT_DIR/overlay"
WORK_DIR="${PMBOOTSTRAP_WORK:-$HOME/.local/var/pmbootstrap}"

echo "=== Phoshdroid rootfs builder ==="

# Initialize pmbootstrap if needed
if ! pmbootstrap config device 2>/dev/null | grep -q "qemu-aarch64"; then
    echo "Initializing pmbootstrap for qemu-aarch64..."
    pmbootstrap init <<INIT_EOF
qemu
aarch64
phosh
INIT_EOF
fi

# Install packages
echo "Building rootfs with Phosh..."
pmbootstrap install --no-fde

# Extract the rootfs
ROOTFS_IMG="$WORK_DIR/chroot_rootfs_qemu-aarch64"
echo "Extracting rootfs..."

STAGING_DIR=$(mktemp -d)
trap "rm -rf $STAGING_DIR" EXIT

# Copy rootfs contents (excluding kernel/firmware we don't need)
sudo tar -cf - \
    --exclude='./boot' \
    --exclude='./lib/modules' \
    --exclude='./lib/firmware' \
    -C "$ROOTFS_IMG" . | tar -xf - -C "$STAGING_DIR"

# Apply overlay (start.sh, config.defaults)
echo "Applying Phoshdroid overlay..."
cp -r "$OVERLAY_DIR/"* "$STAGING_DIR/"
chmod +x "$STAGING_DIR/etc/phoshdroid/start.sh"

# Compress
echo "Compressing rootfs..."
tar -cf - -C "$STAGING_DIR" . | zstd -19 -T0 -o "$OUTPUT"

echo "=== Done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1)) ==="
