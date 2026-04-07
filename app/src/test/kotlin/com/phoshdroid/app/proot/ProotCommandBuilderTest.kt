package com.phoshdroid.app.proot

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProotCommandBuilderTest {

    @Test
    fun `builds default login command`() {
        val builder = ProotCommandBuilder(
            prootDistroPath = "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
            distroName = "postmarketos"
        )

        val cmd = builder.buildLoginCommand(
            startupScript = "/etc/phoshdroid/start.sh"
        )

        assertEquals(
            listOf(
                "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
                "login", "postmarketos",
                "--shared-tmp",
                "--", "/bin/bash", "/etc/phoshdroid/start.sh"
            ),
            cmd
        )
    }

    @Test
    fun `adds sdcard bind mount when enabled`() {
        val builder = ProotCommandBuilder(
            prootDistroPath = "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
            distroName = "postmarketos"
        )

        val cmd = builder.buildLoginCommand(
            startupScript = "/etc/phoshdroid/start.sh",
            bindSdcard = true
        )

        assertTrue(cmd.contains("--bind"))
        val bindIdx = cmd.indexOf("--bind")
        assertEquals("/sdcard:/home/user/sdcard", cmd[bindIdx + 1])
    }

    @Test
    fun `builds registration command`() {
        val builder = ProotCommandBuilder(
            prootDistroPath = "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
            distroName = "postmarketos"
        )

        val cmd = builder.buildInstallCommand(
            tarballPath = "/data/data/com.phoshdroid.app/files/rootfs/rootfs.tar.zst"
        )

        assertEquals(
            listOf(
                "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
                "install",
                "--override-alias", "postmarketos",
                "/data/data/com.phoshdroid.app/files/rootfs/rootfs.tar.zst"
            ),
            cmd
        )
    }

    @Test
    fun `builds environment variables for wayland`() {
        val builder = ProotCommandBuilder(
            prootDistroPath = "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
            distroName = "postmarketos"
        )

        val env = builder.buildEnvironment()

        assertEquals("wayland-0", env["WAYLAND_DISPLAY"])
        assertEquals("/tmp", env["XDG_RUNTIME_DIR"])
        assertTrue(env.containsKey("DISPLAY"))
    }

    @Test
    fun `custom startup script overrides default`() {
        val builder = ProotCommandBuilder(
            prootDistroPath = "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
            distroName = "postmarketos"
        )

        val cmd = builder.buildLoginCommand(
            startupScript = "/home/user/my-start.sh"
        )

        assertTrue(cmd.last() == "/home/user/my-start.sh")
    }
}
