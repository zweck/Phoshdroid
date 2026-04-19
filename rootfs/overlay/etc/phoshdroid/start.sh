#!/bin/bash
# Phoshdroid startup — root-side prep, then drops to UID 1000 for the phosh session.
exec > /tmp/phoshdroid-start.log 2>&1
set -x

# --- root-side prep -------------------------------------------------------
mkdir -p /tmp /run /var/tmp /dev/shm /usr/lib/gdk-pixbuf-2.0/2.10.0 /home/user
chmod 1777 /tmp /var/tmp /dev/shm 2>/dev/null
chown -R user:user /home/user 2>/dev/null || true

# Replace bwrap with a shim that strips sandbox flags and just execs the inner
# command. bwrap's real sandboxing needs CLONE_NEWUSER + seccomp, which proot
# can't emulate. glycin (used by gdk-pixbuf 2.44 for SVG loading) invokes
# bwrap on every image load — without this shim, phosh aborts on the first
# icon. Security is already a no-op under proot; the shim just makes the
# expected command flow through.
if [ -f /etc/phoshdroid/bwrap-shim.sh ] && [ -e /usr/bin/bwrap ]; then
    chmod +x /etc/phoshdroid/bwrap-shim.sh
    cp /usr/bin/bwrap /usr/bin/bwrap.real 2>/dev/null || true
    cp /etc/phoshdroid/bwrap-shim.sh /usr/bin/bwrap
    chmod +x /usr/bin/bwrap
fi
gdk-pixbuf-query-loaders > /usr/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache 2>/dev/null || true

# Recompile gschemas so our 99-phoshdroid.gschema.override wins over the
# pmOS defaults. The shipped rootfs's gschemas.compiled still points phosh's
# wallpaper URIs at blobs-d.svg, and that SVG load goes through glycin's
# bwrap-sandbox codepath which has SIGSEGV'd phosh on dark/light switch.
# Our override forces both light and dark URIs at a pre-rasterised PNG.
# Cheap (milliseconds), idempotent.
glib-compile-schemas /usr/share/glib-2.0/schemas/ 2>/dev/null || true

# Clear PAM blockers.
passwd -d user >/dev/null 2>&1 || true
passwd -d root >/dev/null 2>&1 || true

# Phoc config — mode driven by XLORIE_WIDTH/HEIGHT from the Android launcher.
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
chmod 644 "$PHOC_INI"

# Write the user-session script. This runs under UID 1000 via su, with its own
# clean env. Keeping this as a separate file avoids heredoc-quoting hell.
USER_SCRIPT="/tmp/phoshdroid-user.sh"
cat > "$USER_SCRIPT" <<'USER_EOF'
#!/bin/bash
set -x

export TMPDIR=/tmp
export HOME=/home/user
export DISPLAY=:0

# Wayland compositor env — phoc drives an X11 backend against our embedded
# Termux:X11 server and exports a Wayland socket for phosh/apps to use.
export XDG_RUNTIME_DIR=/tmp
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=Phosh:GNOME
export WAYLAND_DISPLAY=wayland-0
export WLR_BACKENDS=x11
export WLR_RENDERER=pixman
export GIO_USE_VFS=local
export GSK_RENDERER=cairo
export LIBGL_ALWAYS_SOFTWARE=1

# Firefox content-process sandbox uses syscalls proot can't emulate — tabs
# SIGSEGV without these.
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_DISABLE_RDD_SANDBOX=1
export MOZ_DISABLE_GMP_SANDBOX=1
export MOZ_DISABLE_SOCKET_PROCESS=1

# Glycin image loader — disable bwrap sandbox (doesn't work in proot).
export GLYCIN_FORCE_NO_SANDBOX=1
export GLYCIN_SANDBOX_MECHANISM=none

# ProotCommandBuilder sets a fake DBUS_SESSION_BUS_ADDRESS pointing nowhere;
# drop it so dbus-run-session creates a fresh bus.
unset DBUS_SESSION_BUS_ADDRESS

exec dbus-run-session -- sh -c '
    # Let a bare swipe-up dismiss the lockscreen without a PIN.
    gsettings set sm.puri.phosh.lockscreen require-unlock false 2>&1 | head -3
    exec phoc -C /tmp/phoc.ini -E /usr/libexec/phosh
'
USER_EOF
chmod 755 "$USER_SCRIPT"

# XLORIE_WIDTH/HEIGHT are set by the Android launcher but don't cross the
# su -l boundary; write them into a sourced file that the user script reads.
cat > /tmp/phoshdroid-xlorie.env <<ENV_EOF
export XLORIE_WIDTH=${XLORIE_WIDTH}
export XLORIE_HEIGHT=${XLORIE_HEIGHT}
ENV_EOF
# Prepend the XLORIE env to the user script so it's available when phoc
# writes phoc.ini... wait, phoc.ini is already written above as root. But
# keep XLORIE sourced too in case anything downstream looks at it.
chmod 644 /tmp/phoshdroid-xlorie.env

# --- drop to UID 1000 for phosh + apps ----------------------------------
# Nautilus and other GNOME apps hardcode `geteuid() == 0` as "unsupported" and
# exit 95; xdg-desktop-portal rejects /proc/PID/root from root processes.
# Running the phosh session as 'user' (UID 1000) keeps everything non-root.
echo "=== handing off to user session ==="
exec su - user -s /bin/bash -c "source /tmp/phoshdroid-xlorie.env && exec $USER_SCRIPT"
