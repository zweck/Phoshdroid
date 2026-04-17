#!/bin/bash
exec > /tmp/phoshdroid-start.log 2>&1
set -x

export TMPDIR=/tmp
export DISPLAY=:0
export HOME=/root
# Do NOT set GDK_BACKEND — phosh needs wayland (default), only phoc uses X11
export GSK_RENDERER=cairo
export LIBGL_ALWAYS_SOFTWARE=1

mkdir -p /tmp /run /var/tmp /dev/shm /usr/lib/gdk-pixbuf-2.0/2.10.0
chmod 1777 /tmp /var/tmp /dev/shm 2>/dev/null

export XDG_RUNTIME_DIR="/tmp"
export XDG_SESSION_TYPE="wayland"
export XDG_CURRENT_DESKTOP="Phosh:GNOME"
export WAYLAND_DISPLAY="wayland-0"
export WLR_BACKENDS="x11"
export WLR_RENDERER="pixman"
export WLR_LOG_LEVEL=debug
export WAYLAND_DEBUG=server
export GIO_USE_VFS=local

# Route gdk-pixbuf to 2.42. 2.44 loads SVGs via glycin, which spawns bwrap-
# sandboxed loader subprocesses that can't run in our proot environment, and
# phosh aborts on the first icon load.
if [ -f /usr/lib/libgdk_pixbuf-2.0.so.0.4200.12 ]; then
    ln -sf libgdk_pixbuf-2.0.so.0.4200.12 /usr/lib/libgdk_pixbuf-2.0.so.0
fi
# Tell glycin (used by some GTK4 call paths) not to sandbox, in case it still
# gets invoked. Multiple env var names cover different glycin versions.
export GLYCIN_FORCE_NO_SANDBOX=1
export GLYCIN_SANDBOX_MECHANISM=none
gdk-pixbuf-query-loaders > /usr/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache 2>/dev/null || true

# Drive phoc's X11 output size from XLORIE env vars so it matches the phone
OUTPUT_MODE=""
if [ -n "$XLORIE_WIDTH" ] && [ -n "$XLORIE_HEIGHT" ]; then
    OUTPUT_MODE="mode=${XLORIE_WIDTH}x${XLORIE_HEIGHT}"
fi

PHOC_INI="/tmp/phoc.ini"
cat > "$PHOC_INI" <<PHOC_EOF
[core]
xwayland=false

[output:X11-1]
scale=2
$OUTPUT_MODE
PHOC_EOF

# Phosh owns its D-Bus names (org.gnome.ScreenSaver, sm.puri.Phosh, ...) only when
# it has a working user session bus. ProotCommandBuilder sets a fake
# DBUS_SESSION_BUS_ADDRESS that points to nothing — drop it and always spawn a
# fresh session bus via dbus-run-session, matching phosh-session's upstream setup.
unset DBUS_SESSION_BUS_ADDRESS
DBUS_RUN_SESSION="dbus-run-session --"

echo "=== Starting phoc + phosh ==="
echo "XLORIE geometry: ${XLORIE_WIDTH}x${XLORIE_HEIGHT}"
echo "phoc.ini contents:"
cat "$PHOC_INI"

# Clear the user password so PAM auth doesn't block unlock. pmOS rootfs creates
# one 'user' account with a pre-baked password hash; we don't know it and don't
# need it for phone access.
passwd -d user >/dev/null 2>&1 || true
passwd -d root >/dev/null 2>&1 || true

# Disable phosh's PIN requirement so a bare swipe-up unlocks.
# (dconf write is inside dbus-run-session below, after the user session bus exists.)

# (unlock probe moved inside dbus-run-session below so it shares phosh's bus)

exec $DBUS_RUN_SESSION sh -c '
    # dconf now has a working session bus, so we can set the phosh gsetting that
    # lets a bare swipe-up dismiss the lockscreen (no PIN required).
    gsettings set sm.puri.phosh.lockscreen require-unlock false 2>&1 | head -3
    exec phoc -C "'"$PHOC_INI"'" -E "/usr/libexec/phosh"
'
