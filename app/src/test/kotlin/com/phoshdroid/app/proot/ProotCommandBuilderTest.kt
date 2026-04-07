package com.phoshdroid.app.proot

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProotCommandBuilderTest {

    private val nativeLibDir = "/data/app/com.phoshdroid.app/lib/arm64"
    private val prefixDir = "/data/data/com.phoshdroid.app/files/usr"

    private fun builder() = ProotCommandBuilder(
        nativeLibDir = nativeLibDir,
        prefixDir = prefixDir,
        distroName = "postmarketos"
    )

    @Test
    fun `builds default login command using native bash`() {
        val cmd = builder().buildLoginCommand(
            startupScript = "/etc/phoshdroid/start.sh"
        )

        assertEquals("$nativeLibDir/libbash.so", cmd[0])
        assertEquals("$prefixDir/bin/proot-distro", cmd[1])
        assertTrue(cmd.contains("login"))
        assertTrue(cmd.contains("postmarketos"))
        assertTrue(cmd.contains("--shared-tmp"))
        assertEquals("/etc/phoshdroid/start.sh", cmd.last())
    }

    @Test
    fun `adds sdcard bind mount when enabled`() {
        val cmd = builder().buildLoginCommand(
            startupScript = "/etc/phoshdroid/start.sh",
            bindSdcard = true
        )

        assertTrue(cmd.contains("--bind"))
        val bindIdx = cmd.indexOf("--bind")
        assertEquals("/sdcard:/home/user/sdcard", cmd[bindIdx + 1])
    }

    @Test
    fun `builds install command using native bash`() {
        val cmd = builder().buildInstallCommand(
            tarballPath = "/data/data/com.phoshdroid.app/files/rootfs/rootfs.tar"
        )

        assertEquals("$nativeLibDir/libbash.so", cmd[0])
        assertEquals("$prefixDir/bin/proot-distro", cmd[1])
        assertTrue(cmd.contains("install"))
        assertTrue(cmd.contains("--override-alias"))
    }

    @Test
    fun `builds environment with proot exec path`() {
        val env = builder().buildEnvironment()

        assertEquals("wayland-0", env["WAYLAND_DISPLAY"])
        assertEquals("/tmp", env["XDG_RUNTIME_DIR"])
        assertEquals("$nativeLibDir/libproot.so", env["PROOT_EXEC"])
        assertTrue(env["PATH"]!!.contains(nativeLibDir))
    }

    @Test
    fun `custom startup script overrides default`() {
        val cmd = builder().buildLoginCommand(
            startupScript = "/home/user/my-start.sh"
        )

        assertEquals("/home/user/my-start.sh", cmd.last())
    }
}
