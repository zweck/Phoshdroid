package com.phoshdroid.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.system.Os
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.phoshdroid.app.extraction.AssetExtractor
import com.phoshdroid.app.proot.ProotCommandBuilder
import com.phoshdroid.app.proot.ProotDistroManager
import com.phoshdroid.app.proot.ProotService
import com.phoshdroid.app.settings.PhoshdroidPreferences
import com.phoshdroid.app.settings.ProotConfigWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LauncherActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var errorText: TextView

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ACTION_SETTINGS) {
            setContentView(R.layout.activity_launcher)
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
            return
        }

        setContentView(R.layout.activity_launcher)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        errorText = findViewById(R.id.errorText)

        requestNotificationPermission()
        lifecycleScope.launch {
            try {
                bootstrap()
            } catch (e: Exception) {
                showError("Startup failed: ${e.message}\n\n${e.stackTraceToString()}", copyable = true)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private suspend fun bootstrap() {
        val filesDir = applicationContext.filesDir
        val extractor = AssetExtractor()

        // Phase 1: Extract Termux bootstrap
        val bootstrapDir = File(filesDir, "usr")
        if (!File(bootstrapDir, ".extraction_complete").exists()) {
            showProgress(getString(R.string.extracting_termux))
            progressBar.isIndeterminate = true
            val extracted = withContext(Dispatchers.IO) {
                assets.open("bootstrap.bin").use { input ->
                    extractor.extract(input, -1, bootstrapDir)
                }
            }
            if (!extracted) {
                showError("Failed to extract Termux environment.")
                return
            }
        }
        // Create symlinks from prefix/bin to native lib dir binaries
        // (native lib dir is the only place Android allows exec)
        val nativeLibDir = applicationInfo.nativeLibraryDir
        withContext(Dispatchers.IO) {
            val binDir = File(bootstrapDir, "bin")
            binDir.mkdirs()
            mapOf("bash" to "libbash.so", "proot" to "libproot.so").forEach { (name, soName) ->
                val link = File(binDir, name)
                val target = File(nativeLibDir, soName)
                if (target.exists() && !link.exists()) {
                    Runtime.getRuntime().exec(arrayOf("ln", "-sf", target.absolutePath, link.absolutePath)).waitFor()
                }
            }
        }

        // Phase 2: Extract pmOS rootfs
        val rootfsExtractDir = File(filesDir, "rootfs")
        if (!File(rootfsExtractDir, ".extraction_complete").exists()) {
            showProgress(getString(R.string.extracting_rootfs))

            val availableBytes = StatFs(filesDir.absolutePath).availableBytes
            val requiredBytes = 1_500_000_000L
            if (availableBytes < requiredBytes) {
                showError(getString(
                    R.string.error_no_space,
                    formatSize(requiredBytes),
                    formatSize(availableBytes)
                ))
                return
            }

            progressBar.isIndeterminate = true
            val extracted = withContext(Dispatchers.IO) {
                assets.open("rootfs.bin").use { input ->
                    extractor.extract(input, -1, rootfsExtractDir)
                }
            }
            if (!extracted) {
                showError("Failed to extract postmarketOS.")
                return
            }
        }

        // Phase 3: Set up rootfs for proot (bypass proot-distro, call proot directly)
        statusText.text = getString(R.string.preparing_desktop)
        progressBar.visibility = View.GONE

        val prefixDir = "${filesDir}/usr"
        val rootfsDir = File(prefixDir, "var/lib/proot-distro/installed-rootfs/${ProotService.DISTRO_NAME}")
        if (!rootfsDir.exists()) {
            withContext(Dispatchers.IO) {
                rootfsDir.mkdirs()
                // Move extracted rootfs into proot's expected location
                File(filesDir, "rootfs").listFiles()?.forEach { file ->
                    if (file.name != ".extraction_complete") {
                        file.renameTo(File(rootfsDir, file.name))
                    }
                }
            }
        }

        // Ensure shared tmp dir exists for Wayland socket
        File(prefixDir, "tmp").mkdirs()

        // Phase 4: Write config
        val prefs = PhoshdroidPreferences(this)
        val configFile = File(filesDir, "proot-distro/installed-rootfs/postmarketos/etc/phoshdroid/config")
        ProotConfigWriter(configFile).write(prefs.toProotConfig())

        // Default Termux:X11 to direct-touch input mode — phone users expect finger
        // taps to act as touches, not move a trackpad cursor. "3" = Direct touch.
        // Also hide the additional keyboard toolbar so phosh owns the full surface
        // height — its lockscreen unlock gesture needs to originate from the bottom edge.
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putString("touchMode", "3")
            // Keep the Termux:X11 shortcut toolbar (ESC, CTRL, arrows, etc.) visible.
            // We carve out a bottom gap below phosh anyway for the edge swipe so the
            // toolbar doesn't overlap phosh's usable area.
            .putBoolean("showAdditionalKbd", true)
            .putBoolean("additionalKbdVisible", true)
            // Immersive sticky fullscreen — keeps Android nav bar hidden so bottom-edge
            // swipes go to phosh instead of being consumed by the Android home gesture.
            .putBoolean("fullscreen", true)
            .putBoolean("hideCutout", true)
            .apply()

        // Phase 5: Start X11 server, then proot, then launch display activity
        statusText.text = getString(R.string.starting_desktop)

        // Start the Termux:X11 server
        val tmpDir = File(prefixDir, "tmp")
        tmpDir.mkdirs()
        val x11UnixDir = File(tmpDir, ".X11-unix")
        x11UnixDir.mkdirs()
        // Clean up stale X/Wayland/dbus sockets + lock files left behind when the
        // previous app process was killed. Without this, the new in-process X
        // server can't rebind :0 and phoc aborts with "Failed to open xcb
        // connection" on the very first child launch.
        listOf(
            File(x11UnixDir, "X0"),
            File(tmpDir, ".X0-lock"),
            File(tmpDir, "wayland-0"),
            File(tmpDir, "wayland-0.lock"),
            File(tmpDir, "dbus-session")
        ).forEach { stale ->
            if (stale.exists()) {
                android.util.Log.i("Phoshdroid", "Removing stale ${stale.name}")
                stale.delete()
            }
        }
        try {
            Os.setenv("TMPDIR", tmpDir.absolutePath, true)
            Os.setenv("TERMUX_X11_OVERRIDE_PACKAGE", packageName, true)
            // XKB keyboard config from the rootfs
            val xkbDir = File(rootfsDir, "usr/share/X11/xkb")
            if (xkbDir.exists()) {
                Os.setenv("XKB_CONFIG_ROOT", xkbDir.absolutePath, true)
            }
            // Tell the X server to boot at the real device size so phoc's initial
            // X11 output matches the surface. Phoc's X11 backend doesn't resize
            // when the root window later changes.
            // Leave a bottom gap below phosh's surface so a swipe that starts in
            // that black area and enters phosh registers as an edge swipe — phosh's
            // lockscreen unlock needs the gesture to begin at the surface's bottom.
            val metrics = resources.displayMetrics
            val dw = metrics.widthPixels
            val dh = metrics.heightPixels - TOP_GAP_PX - BOTTOM_GAP_PX
            if (dw > 0 && dh > 0) {
                Os.setenv("XLORIE_WIDTH", dw.toString(), true)
                Os.setenv("XLORIE_HEIGHT", dh.toString(), true)
                android.util.Log.e("Phoshdroid", "XLORIE geometry: ${dw}x${dh}")
            }
            android.util.Log.e("Phoshdroid", "=== Phase 5: About to start X11 server ===")

            // First test: can we even load the CmdEntryPoint class?
            val cls = Class.forName("com.termux.x11.CmdEntryPoint")
            android.util.Log.e("Phoshdroid", "CmdEntryPoint class loaded OK: ${cls.name}")

            // Start X11 server with full constructor + Looper (needed for work queue)
            Thread({
                android.os.Looper.prepare()
                try {
                    val ctor = com.termux.x11.CmdEntryPoint::class.java
                        .getDeclaredConstructor(Array<String>::class.java)
                    ctor.isAccessible = true
                    ctor.newInstance(arrayOf(":0"))
                    android.util.Log.e("Phoshdroid", "X11 server running with Looper")
                    android.os.Looper.loop() // Keep alive for work queue processing
                } catch (e: Throwable) {
                    android.util.Log.e("Phoshdroid", "X11 server error: ${e.message}", e)
                }
            }, "X11Server").start()

            // Wait for X11 socket
            withContext(Dispatchers.IO) {
                val socketFile = File(prefixDir, "tmp/.X11-unix/X0")
                var attempts = 0
                while (!socketFile.exists() && attempts < 20) {
                    Thread.sleep(500)
                    attempts++
                }
                android.util.Log.e("Phoshdroid", "X11 socket exists: ${socketFile.exists()}")
            }
        } catch (e: Throwable) {
            android.util.Log.e("Phoshdroid", "X11 setup failed", e)
        }

        // Launch the X11 display activity
        android.util.Log.e("Phoshdroid", "Launching X11 activity...")
        try {
            val waylandIntent = Intent(this, Class.forName("com.termux.x11.MainActivity"))
                .putExtra("phoshdroid_top_padding_px", TOP_GAP_PX)
                .putExtra("phoshdroid_bottom_padding_px", BOTTOM_GAP_PX)
            startActivity(waylandIntent)
        } catch (e: Exception) {
            statusText.text = "Display failed: ${e.message}"
        }

        // Start proot
        android.util.Log.e("Phoshdroid", "Starting ProotService...")
        ProotService.start(this)
        Toast.makeText(this, getString(R.string.welcome_toast), Toast.LENGTH_LONG).show()
    }

    private fun showProgress(message: String) {
        statusText.text = message
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        errorText.visibility = View.GONE
    }

    private fun showError(message: String, copyable: Boolean = false) {
        statusText.visibility = View.GONE
        progressBar.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = message

        if (copyable) {
            errorText.setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Phoshdroid Error", message))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return if (mb > 1024) "${mb / 1024} GB" else "$mb MB"
    }

    companion object {
        const val ACTION_SETTINGS = "com.phoshdroid.app.SETTINGS"
        // Pixel gaps reserved above and below phosh's X11 surface. Enough space that
        // a bottom-edge swipe originating in the gap crosses into phosh and registers
        // as an edge gesture for lockscreen unlock. Top gap keeps phosh clear of the
        // status bar / camera cutout.
        private const val TOP_GAP_PX = 160
        private const val BOTTOM_GAP_PX = 200
    }
}
