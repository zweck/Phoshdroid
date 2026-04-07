#!/bin/bash
# Phoshdroid startup script — runs inside the pmOS proot environment.
# Reads config from /etc/phoshdroid/config, starts phoc + phosh.

set -e

CONFIG_FILE="/etc/phoshdroid/config"
if [ -f "$CONFIG_FILE" ]; then
    . "$CONFIG_FILE"
fi

# Defaults
: "${SCALE_FACTOR:=1.0}"
: "${ORIENTATION:=auto}"
: "${DE:=phosh}"
: "${WAYLAND_DISPLAY:=wayland-0}"

export XDG_RUNTIME_DIR="/tmp"
export WAYLAND_DISPLAY
export XDG_SESSION_TYPE="wayland"
export XDG_CURRENT_DESKTOP="Phosh:GNOME"
export DISPLAY=":0"

# DPI override
if [ -n "$DPI_OVERRIDE" ]; then
    export GDK_DPI_SCALE="$DPI_OVERRIDE"
fi

# Run custom startup script if configured
if [ -n "$CUSTOM_STARTUP" ] && [ -f "$CUSTOM_STARTUP" ]; then
    bash "$CUSTOM_STARTUP"
fi

# Start dbus session bus
if [ -z "$DBUS_SESSION_BUS_ADDRESS" ]; then
    eval "$(dbus-launch --sh-syntax)"
    export DBUS_SESSION_BUS_ADDRESS
fi

# Write phoc config
PHOC_INI="/tmp/phoc.ini"
cat > "$PHOC_INI" <<PHOC_EOF
[core]
xwayland=false

[output:WL-1]
scale=$SCALE_FACTOR
PHOC_EOF

if [ "$ORIENTATION" = "landscape" ]; then
    echo "transform=90" >> "$PHOC_INI"
fi

# Start phoc (nested Wayland compositor) then phosh
case "$DE" in
    phosh)
        export WLR_BACKENDS="wayland"
        phoc -C "$PHOC_INI" -E "phosh" &
        PHOC_PID=$!
        wait $PHOC_PID
        ;;
    xfce4)
        export WLR_BACKENDS="wayland"
        phoc -C "$PHOC_INI" -E "startxfce4" &
        wait $!
        ;;
    sxmo)
        export WLR_BACKENDS="wayland"
        sxmo_winit.sh &
        wait $!
        ;;
    plasma-mobile)
        export WLR_BACKENDS="wayland"
        phoc -C "$PHOC_INI" -E "startplasma-wayland" &
        wait $!
        ;;
esac
