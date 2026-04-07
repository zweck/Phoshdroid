package com.phoshdroid.app.proot

class ProotCommandBuilder(
    private val nativeLibDir: String,
    private val prefixDir: String,
    private val distroName: String
) {
    // Binaries in nativeLibDir are executable (extracted by Android from APK)
    // Scripts and data in prefixDir are NOT executable (app data storage)
    private val bashPath = "$nativeLibDir/libbash.so"
    private val prootDistroScript = "$prefixDir/bin/proot-distro"

    fun buildLoginCommand(
        startupScript: String,
        bindSdcard: Boolean = false
    ): List<String> {
        // Run proot-distro as a bash script via the native lib bash binary
        val cmd = mutableListOf(
            bashPath,
            prootDistroScript,
            "login", distroName,
            "--shared-tmp"
        )

        if (bindSdcard) {
            cmd.add("--bind")
            cmd.add("/sdcard:/home/user/sdcard")
        }

        cmd.add("--")
        cmd.add("/bin/bash")
        cmd.add(startupScript)

        return cmd
    }

    fun buildInstallCommand(tarballPath: String): List<String> {
        return listOf(
            bashPath,
            prootDistroScript,
            "install",
            "--override-alias", distroName,
            tarballPath
        )
    }

    fun buildEnvironment(): Map<String, String> {
        return mapOf(
            "WAYLAND_DISPLAY" to "wayland-0",
            "XDG_RUNTIME_DIR" to "/tmp",
            "DISPLAY" to ":0",
            "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/tmp/dbus-session",
            "PULSE_SERVER" to "tcp:127.0.0.1:4713",
            // proot-distro needs to find proot binary in nativeLibDir
            "PROOT_EXEC" to "$nativeLibDir/libproot.so",
            "PREFIX" to prefixDir,
            "HOME" to "$prefixDir/home",
            "PATH" to "$nativeLibDir:$prefixDir/bin:/usr/bin:/bin"
        )
    }
}
