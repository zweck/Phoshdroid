package com.phoshdroid.app.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProotConfigWriterTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `writes default config`() {
        val configFile = File(tempDir, "etc/phoshdroid/config")
        val writer = ProotConfigWriter(configFile)

        writer.write(
            ProotConfig(
                scaleFactor = 1.0f,
                orientation = Orientation.AUTO,
                dpiOverride = null,
                desktopEnvironment = DesktopEnvironment.PHOSH
            )
        )

        assertTrue(configFile.exists())
        val content = configFile.readText()
        assertTrue(content.contains("SCALE_FACTOR=1.0"))
        assertTrue(content.contains("ORIENTATION=auto"))
        assertTrue(content.contains("DE=phosh"))
        assertFalse(content.contains("DPI_OVERRIDE"))
    }

    @Test
    fun `writes dpi override when set`() {
        val configFile = File(tempDir, "etc/phoshdroid/config")
        val writer = ProotConfigWriter(configFile)

        writer.write(
            ProotConfig(
                scaleFactor = 1.5f,
                orientation = Orientation.LANDSCAPE,
                dpiOverride = 320,
                desktopEnvironment = DesktopEnvironment.PHOSH
            )
        )

        val content = configFile.readText()
        assertTrue(content.contains("SCALE_FACTOR=1.5"))
        assertTrue(content.contains("ORIENTATION=landscape"))
        assertTrue(content.contains("DPI_OVERRIDE=320"))
    }

    @Test
    fun `creates parent directories`() {
        val configFile = File(tempDir, "deep/nested/path/config")
        val writer = ProotConfigWriter(configFile)

        writer.write(ProotConfig())

        assertTrue(configFile.exists())
    }

    @Test
    fun `overwrites existing config`() {
        val configFile = File(tempDir, "config")
        configFile.writeText("OLD_KEY=old_value\n")
        val writer = ProotConfigWriter(configFile)

        writer.write(ProotConfig(scaleFactor = 2.0f))

        val content = configFile.readText()
        assertFalse(content.contains("OLD_KEY"))
        assertTrue(content.contains("SCALE_FACTOR=2.0"))
    }
}
