#!/bin/bash
# Phoshdroid startup — root-side prep, then drops to UID 1000 for the phosh session.
exec > /tmp/phoshdroid-start.log 2>&1
# Prefix every traced command with epoch.fractional-seconds + line number so
# we can pin down which step is slow when phosh-ready takes minutes instead
# of the expected ~6s. Without this the trace is undated and a 2-minute
# chown looks identical to a 2-second one.
export PS4='+ [$(date +%s.%N | cut -c1-14)] L${LINENO}: '
set -x

# --- root-side prep -------------------------------------------------------
mkdir -p /tmp /run /var/tmp /dev/shm /usr/lib/gdk-pixbuf-2.0/2.10.0 /home/user
chmod 1777 /tmp /var/tmp /dev/shm 2>/dev/null

# chown -R user:user /home/user used to run unconditionally on every launch.
# On a populated profile (~200 MB / 700 files including .mozilla and .cache)
# this took 2-3 minutes under proot — every chown syscall pays for proot's
# translator, and the tree only grows as the user installs/uses apps. Gate
# it behind a marker so it runs once on first boot (or after a deliberate
# reset by deleting the marker) and is a no-op afterwards. The on-disk uid
# is stable across launches; proot doesn't rewrite it.
CHOWN_MARKER=/home/user/.phoshdroid-chowned
if [ ! -f "$CHOWN_MARKER" ]; then
    echo "first-boot chown of /home/user (one-time; can take a few minutes on a populated profile)"
    chown -R user:user /home/user 2>/dev/null || true
    # Marker itself ends up user-owned by the chown above, but write it
    # explicitly so a partial chown still leaves a sensible flag.
    touch "$CHOWN_MARKER" 2>/dev/null || true
    chown user:user "$CHOWN_MARKER" 2>/dev/null || true
fi

# Replace bwrap with a shim that strips sandbox flags and just execs the inner
# command. bwrap's real sandboxing needs CLONE_NEWUSER + seccomp, which proot
# can't emulate. glycin (used by gdk-pixbuf 2.44 for SVG loading) invokes
# bwrap on every image load — without this shim, phosh aborts on the first
# icon. Security is already a no-op under proot; the shim just makes the
# expected command flow through.
if [ -f /etc/phoshdroid/bwrap-shim.sh ] && [ -e /usr/bin/bwrap ]; then
    # Only swap when /usr/bin/bwrap is still the original ELF, not our
    # shebang-prefixed shim. Avoids copying a multi-MB binary on every launch.
    if ! head -c2 /usr/bin/bwrap 2>/dev/null | grep -q '#!'; then
        chmod +x /etc/phoshdroid/bwrap-shim.sh
        cp /usr/bin/bwrap /usr/bin/bwrap.real 2>/dev/null || true
        cp /etc/phoshdroid/bwrap-shim.sh /usr/bin/bwrap
        chmod +x /usr/bin/bwrap
    fi
fi
# Regenerate the gdk-pixbuf loader cache only if a loader .so is newer than
# the cache, or the cache is missing. Under proot this tool takes several
# seconds; skipping it on unchanged installs shaves visible time off cold
# starts.
pixbuf_dir=/usr/lib/gdk-pixbuf-2.0/2.10.0
pixbuf_cache="$pixbuf_dir/loaders.cache"
if [ ! -f "$pixbuf_cache" ] || \
    [ -n "$(find "$pixbuf_dir/loaders" -name '*.so' -newer "$pixbuf_cache" 2>/dev/null | head -n1)" ]; then
    gdk-pixbuf-query-loaders > "$pixbuf_cache" 2>/dev/null || true
fi

# Recompile gschemas so our 99-phoshdroid.gschema.override wins over the
# pmOS defaults. Only runs when an .xml or .override is newer than
# gschemas.compiled — which is the case the first time our overlay lands
# on a fresh rootfs, and not on subsequent launches.
schemas_dir=/usr/share/glib-2.0/schemas
schemas_compiled="$schemas_dir/gschemas.compiled"
if [ ! -f "$schemas_compiled" ] || \
    [ -n "$(find "$schemas_dir" \( -name '*.xml' -o -name '*.override' \) -newer "$schemas_compiled" 2>/dev/null | head -n1)" ]; then
    glib-compile-schemas "$schemas_dir" 2>/dev/null || true
fi

# Clear PAM blockers.
passwd -d user >/dev/null 2>&1 || true
passwd -d root >/dev/null 2>&1 || true

# DNS — the rootfs ships with whatever resolv.conf was current when the
# tarball was built (typically a LAN router IP from the build machine),
# and there's no NetworkManager inside the proot to refresh it because
# we have no system bus. Hardcode public resolvers so Firefox / apt /
# anything else inside phosh can actually resolve hostnames regardless
# of which Android network is active (Wi-Fi, cellular, VPN). Cloudflare
# + Google chosen because both work over IPv4 and IPv6 and both honour
# Android's per-uid VPN routing (so traffic still goes through whatever
# interface the host has selected).
cat > /etc/resolv.conf <<RESOLV_EOF
# Phoshdroid: managed by /etc/phoshdroid/start.sh; edits will be
# overwritten on next launch. To use a different resolver, edit the
# heredoc in start.sh.
nameserver 1.1.1.1
nameserver 1.0.0.1
nameserver 8.8.8.8
nameserver 8.8.4.4
RESOLV_EOF
chmod 644 /etc/resolv.conf

# pmroot fake-root listener.
#
# start.sh runs as fake-UID-0 (proot's -0 flag), but we drop to UID 1000
# via `su - user` before exec'ing phosh because GNOME apps refuse to run
# as root. That leaves the user with no path to apk / passwd / mkfs etc:
# Android's untrusted_app sandbox sets PR_SET_NO_NEW_PRIVS=1, which
# blocks sudo/su from elevating, and proot can't fake UID 0 again once
# we've explicitly setuid'd down.
#
# Solution: while we're still UID 0, fork a long-running bash that reads
# command lines from a FIFO and runs each one as the listener's own
# (root) UID. The user-side `pmroot` wrapper writes commands in and
# collects results. Functionally equivalent to a passwordless sudo
# scoped to commands the user explicitly runs through pmroot.
#
# Security model: any process at UID 1000 inside the rootfs can write to
# the FIFO, which means anything the user can run can become 'root'.
# That's already the security model of proot — there's no real privilege
# boundary because it's all simulation — so pmroot doesn't weaken
# anything that wasn't already permeable.
mkdir -p /tmp/pmroot
chmod 1777 /tmp/pmroot
rm -f /tmp/pmroot/in
mkfifo /tmp/pmroot/in
chmod 622 /tmp/pmroot/in

# The listener loop runs forever, reading one command-line per iteration.
# Backgrounded with disown so it survives the shell that started it; nohup
# detaches it from the terminal once we drop privs and exec into su.
# Stdout/stderr go to a debug log so a misbehaving listener can be
# inspected without a live shell.
nohup bash -c '
    exec >>/tmp/pmroot/listener.log 2>&1
    echo "=== pmroot listener started at $(date) ==="
    while true; do
        # Re-open the FIFO every iteration. The writer side closes after
        # each command, which would make read() return EOF; the loop
        # would spin instead of blocking.
        if ! IFS= read -r line < /tmp/pmroot/in; then
            sleep 0.1
            continue
        fi
        # Split on first colon: <id>:<quoted-command>
        id="${line%%:*}"
        cmd="${line#*:}"
        out="/tmp/pmroot/out.$id"
        done_marker="/tmp/pmroot/done.$id"
        echo "[$(date +%T)] running $id: $cmd"
        # eval so the printf '%q' quoting from the wrapper survives.
        bash -c "$cmd" >"$out" 2>&1 || true
        rc=$?
        echo "$rc" >"$done_marker"
        echo "[$(date +%T)] done $id rc=$rc"
    done
' >/dev/null 2>&1 &
disown
echo "pmroot listener pid: $!"

# Phoc config — mode driven by XLORIE_WIDTH/HEIGHT from the Android launcher.
# If the launcher couldn't determine the display size early enough (e.g. the
# activity came up while Android was still in Doze), fall back to a sane
# 9:16-ish default rather than letting phoc inherit wlroots' 1024x768. A
# wrong-but-plausible mode is dramatically less broken than the default.
OUTPUT_MODE=""
if [ -n "$XLORIE_WIDTH" ] && [ -n "$XLORIE_HEIGHT" ] && [ "$XLORIE_WIDTH" -gt 0 ] && [ "$XLORIE_HEIGHT" -gt 0 ]; then
    OUTPUT_MODE="mode=${XLORIE_WIDTH}x${XLORIE_HEIGHT}"
else
    echo "WARN: XLORIE_WIDTH/HEIGHT empty (got '$XLORIE_WIDTH'x'$XLORIE_HEIGHT'); falling back to 1080x2364" >&2
    OUTPUT_MODE="mode=1080x2004"
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
