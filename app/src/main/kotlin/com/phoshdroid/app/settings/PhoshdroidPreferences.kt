package com.phoshdroid.app.settings

import android.content.Context
import android.content.SharedPreferences

class PhoshdroidPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("phoshdroid_prefs", Context.MODE_PRIVATE)

    var scaleFactor: Float
        get() = prefs.getFloat("scale_factor", 1.0f)
        set(value) = prefs.edit().putFloat("scale_factor", value).apply()

    var orientation: Orientation
        get() = Orientation.entries.find { it.value == prefs.getString("orientation", "auto") }
            ?: Orientation.AUTO
        set(value) = prefs.edit().putString("orientation", value.value).apply()

    var dpiOverride: Int?
        get() = prefs.getInt("dpi_override", -1).takeIf { it > 0 }
        set(value) = prefs.edit().putInt("dpi_override", value ?: -1).apply()

    var desktopEnvironment: DesktopEnvironment
        get() = DesktopEnvironment.entries.find {
            it.value == prefs.getString("de", "phosh")
        } ?: DesktopEnvironment.PHOSH
        set(value) = prefs.edit().putString("de", value.value).apply()

    var bindSdcard: Boolean
        get() = prefs.getBoolean("bind_sdcard", true)
        set(value) = prefs.edit().putBoolean("bind_sdcard", value).apply()

    var customStartupScript: String?
        get() = prefs.getString("custom_startup", null)
        set(value) = prefs.edit().putString("custom_startup", value).apply()

    var showTerminal: Boolean
        get() = prefs.getBoolean("show_terminal", false)
        set(value) = prefs.edit().putBoolean("show_terminal", value).apply()

    fun toProotConfig(): ProotConfig {
        return ProotConfig(
            scaleFactor = scaleFactor,
            orientation = orientation,
            dpiOverride = dpiOverride,
            desktopEnvironment = desktopEnvironment,
            customStartupScript = customStartupScript
        )
    }
}
