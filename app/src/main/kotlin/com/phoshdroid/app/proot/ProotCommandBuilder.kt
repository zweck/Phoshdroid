package com.phoshdroid.app.proot

class ProotCommandBuilder(
    private val prootDistroPath: String,
    private val distroName: String
) {

    fun buildLoginCommand(
        startupScript: String,
        bindSdcard: Boolean = false
    ): List<String> {
        val cmd = mutableListOf(
            prootDistroPath,
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
            prootDistroPath,
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
            "PULSE_SERVER" to "tcp:127.0.0.1:4713"
        )
    }
}
