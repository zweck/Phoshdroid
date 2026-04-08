#!/bin/bash
# Phoshdroid startup script — runs inside the pmOS proot environment.
# Reads config from /etc/phoshdroid/config, starts phoc + phosh.

# Log everything for debugging
exec > /tmp/phoshdroid-start.log 2>&1
set -x

CONFIG_FILE="/etc/phoshdroid/config"
if [ -f "$CONFIG_FILE" ]; then
    . "$CONFIG_FILE"
fi

# Defaults
: "${SCALE_FACTOR:=2}"
: "${ORIENTATION:=auto}"
: "${DE:=phosh}"

export XDG_RUNTIME_DIR="/tmp"
export XDG_SESSION_TYPE="wayland"
export XDG_CURRENT_DESKTOP="Phosh:GNOME"
export DISPLAY=":0"
export WAYLAND_DISPLAY="wayland-0"

# DPI override
if [ -n "$DPI_OVERRIDE" ]; then
    export GDK_DPI_SCALE="$DPI_OVERRIDE"
fi

# Termux:X11 is an X11 server — phoc should use the X11 backend
export WLR_BACKENDS="x11"

# Write phoc config
PHOC_INI="/tmp/phoc.ini"
cat > "$PHOC_INI" <<PHOC_EOF
[core]
xwayland=false

[output:X11-1]
scale=$SCALE_FACTOR
PHOC_EOF

if [ "$ORIENTATION" = "landscape" ]; then
    echo "transform=90" >> "$PHOC_INI"
fi

# Start dbus session bus
if command -v dbus-launch >/dev/null 2>&1; then
    if [ -z "$DBUS_SESSION_BUS_ADDRESS" ]; then
        eval "$(dbus-launch --sh-syntax)"
        export DBUS_SESSION_BUS_ADDRESS
    fi
fi

echo "=== Starting phoc with X11 backend ==="
echo "DISPLAY=$DISPLAY"
echo "WAYLAND_DISPLAY=$WAYLAND_DISPLAY"
echo "WLR_BACKENDS=$WLR_BACKENDS"

# Start phoc (Wayland compositor using X11 backend from Termux:X11)
# phosh is at /usr/libexec/phosh
phoc -C "$PHOC_INI" -E "/usr/libexec/phosh" &
PHOC_PID=$!
echo "phoc started with PID $PHOC_PID"
wait $PHOC_PID
echo "phoc exited with code $?"
