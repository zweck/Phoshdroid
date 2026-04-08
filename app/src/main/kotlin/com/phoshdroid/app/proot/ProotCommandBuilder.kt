package com.phoshdroid.app.proot

/**
 * Builds proot commands. Uses proot directly from nativeLibDir.
 * All shared lib deps are also in nativeLibDir with patched unversioned names.
 */
class ProotCommandBuilder(
    private val nativeLibDir: String,
    private val prefixDir: String,
    private val distroName: String
) {
    private val prootPath = "$nativeLibDir/libproot.so"
    private val rootfsDir get() = "$prefixDir/var/lib/proot-distro/installed-rootfs/$distroName"

    fun buildLoginCommand(
        startupScript: String,
        bindSdcard: Boolean = false
    ): List<String> {
        // Call proot directly — no bash wrapper needed since proot is in nativeLibDir
        val cmd = mutableListOf(
            prootPath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-w", "/root",
            "-r", rootfsDir,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$prefixDir/tmp:/tmp"
        )

        if (bindSdcard) {
            cmd.add("-b")
            cmd.add("/sdcard:/home/user/sdcard")
        }

        cmd.add("/bin/bash")
        cmd.add(startupScript)

        return cmd
    }

    fun buildEnvironment(): Map<String, String> {
        return mapOf(
            "WAYLAND_DISPLAY" to "wayland-0",
            "XDG_RUNTIME_DIR" to "/tmp",
            "DISPLAY" to ":0",
            "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/tmp/dbus-session",
            "PULSE_SERVER" to "tcp:127.0.0.1:4713",
            "LD_LIBRARY_PATH" to "$nativeLibDir:$prefixDir/lib",
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            // proot needs a writable temp dir — override hardcoded Termux path
            "PROOT_TMP_DIR" to "$prefixDir/tmp",
            "TMPDIR" to "$prefixDir/tmp",
            // proot's ELF loader — must be executable (in nativeLibDir)
            "PROOT_LOADER" to "$nativeLibDir/libproot-loader.so"
        )
    }
}
