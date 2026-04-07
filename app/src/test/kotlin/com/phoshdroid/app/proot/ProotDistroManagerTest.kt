package com.phoshdroid.app.proot

import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProotDistroManagerTest {

    @TempDir
    lateinit var tempDir: File

    private fun createManager(
        processRunner: ProcessRunner = mockk(relaxed = true)
    ): ProotDistroManager {
        val prefixDir = File(tempDir, "usr").also { it.mkdirs() }
        val rootfsDir = File(tempDir, "rootfs").also { it.mkdirs() }
        return ProotDistroManager(
            commandBuilder = ProotCommandBuilder(
                prootDistroPath = "${prefixDir.absolutePath}/bin/proot-distro",
                distroName = "postmarketos"
            ),
            installedRootfsDir = File(tempDir, "proot-distro/installed-rootfs"),
            rootfsTarball = File(rootfsDir, "rootfs.tar.zst"),
            processRunner = processRunner
        )
    }

    @Test
    fun `isInstalled returns false when rootfs directory does not exist`() {
        val manager = createManager()
        assertFalse(manager.isInstalled())
    }

    @Test
    fun `isInstalled returns true when rootfs directory exists`() {
        File(tempDir, "proot-distro/installed-rootfs/postmarketos").mkdirs()
        val manager = createManager()
        assertTrue(manager.isInstalled())
    }

    @Test
    fun `install runs proot-distro install command`() {
        val runner = mockk<ProcessRunner>(relaxed = true)
        every { runner.run(any(), any()) } returns ProcessResult(0, "")
        val manager = createManager(processRunner = runner)

        manager.install()

        verify {
            runner.run(
                match { it.contains("install") && it.contains("--override-alias") },
                any()
            )
        }
    }

    @Test
    fun `login starts process and returns handle`() {
        val mockProcess = mockk<Process>(relaxed = true)
        val runner = mockk<ProcessRunner>(relaxed = true)
        every { runner.start(any(), any()) } returns mockProcess
        val manager = createManager(processRunner = runner)

        val process = manager.login("/etc/phoshdroid/start.sh")

        assertNotNull(process)
        verify {
            runner.start(
                match { it.contains("login") && it.last() == "/etc/phoshdroid/start.sh" },
                any()
            )
        }
    }

    @Test
    fun `login passes environment variables`() {
        val runner = mockk<ProcessRunner>(relaxed = true)
        val mockProcess = mockk<Process>(relaxed = true)
        every { runner.start(any(), any()) } returns mockProcess
        val manager = createManager(processRunner = runner)

        manager.login("/etc/phoshdroid/start.sh")

        verify {
            runner.start(
                any(),
                match { env -> env["WAYLAND_DISPLAY"] == "wayland-0" }
            )
        }
    }
}
