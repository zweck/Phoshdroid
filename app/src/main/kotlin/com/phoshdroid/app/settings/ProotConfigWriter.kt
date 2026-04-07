package com.phoshdroid.app.settings

import java.io.File

enum class Orientation(val value: String) {
    AUTO("auto"), LANDSCAPE("landscape"), PORTRAIT("portrait")
}

enum class DesktopEnvironment(val value: String) {
    PHOSH("phosh"), XFCE4("xfce4"), SXMO("sxmo"), PLASMA_MOBILE("plasma-mobile")
}

data class ProotConfig(
    val scaleFactor: Float = 1.0f,
    val orientation: Orientation = Orientation.AUTO,
    val dpiOverride: Int? = null,
    val desktopEnvironment: DesktopEnvironment = DesktopEnvironment.PHOSH,
    val customStartupScript: String? = null
)

class ProotConfigWriter(private val configFile: File) {

    fun write(config: ProotConfig) {
        configFile.parentFile?.mkdirs()

        val lines = mutableListOf<String>()
        lines.add("SCALE_FACTOR=${config.scaleFactor}")
        lines.add("ORIENTATION=${config.orientation.value}")
        lines.add("DE=${config.desktopEnvironment.value}")

        config.dpiOverride?.let {
            lines.add("DPI_OVERRIDE=$it")
        }

        config.customStartupScript?.let {
            lines.add("CUSTOM_STARTUP=$it")
        }

        configFile.writeText(lines.joinToString("\n") + "\n")
    }
}
