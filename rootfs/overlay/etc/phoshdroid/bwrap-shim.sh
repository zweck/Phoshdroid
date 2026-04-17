#!/bin/sh
# bwrap shim for proot — bwrap's real sandboxing needs CLONE_NEWUSER / seccomp,
# neither of which proot can emulate. glycin and other Flatpak-style callers
# invoke /usr/bin/bwrap with a long argument list ending in the real command.
# This shim parses those flags, discards them, and execs the tail.
#
# Only the bwrap flags glycin actually uses are handled. Anything unrecognised
# is treated as "the command starts here".

set -eu

EXTRA_ENV=""

while [ $# -gt 0 ]; do
    case "$1" in
        # 0-arg flags
        --unshare-all|--unshare-user|--unshare-user-try|--unshare-ipc|--unshare-pid \
        |--unshare-net|--unshare-uts|--unshare-cgroup|--unshare-cgroup-try \
        |--share-net|--die-with-parent|--as-pid-1|--clearenv|--new-session \
        |--proc-readonly|--disable-userns|--assert-userns-disabled)
            shift ;;
        # 1-arg flags
        --chdir|--dev|--tmpfs|--proc|--mqueue|--lock-file|--sync-fd|--exec-label \
        |--file-label|--seccomp|--add-seccomp-fd|--block-fd|--userns-block-fd \
        |--info-fd|--json-status-info-fd|--uid|--gid|--userns|--userns2|--pidns \
        |--hostname|--overlay-src|--argv0|--cap-add|--cap-drop)
            shift 2 ;;
        # 2-arg flags
        --bind|--bind-try|--dev-bind|--dev-bind-try|--ro-bind|--ro-bind-try \
        |--remount-ro|--symlink|--overlay|--tmp-overlay|--ro-overlay \
        |--perms|--size|--file|--bind-data|--ro-bind-data)
            shift 3 ;;
        # --setenv NAME VALUE is 2 args
        --setenv)
            EXTRA_ENV="$EXTRA_ENV $2=$3"
            shift 3 ;;
        --unsetenv)
            shift 2 ;;
        # End of bwrap flags — the rest is the command + its args
        --)
            shift
            break ;;
        # Anything else that doesn't start with -- is the command.
        --*)
            # Unknown flag; try to skip just it (best effort).
            shift ;;
        *)
            break ;;
    esac
done

if [ $# -eq 0 ]; then
    exit 0
fi

# Forward the --setenv NAME=VALUE pairs into the child env.
for pair in $EXTRA_ENV; do
    export "$pair"
done

exec "$@"
