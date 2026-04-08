package com.phoshdroid.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
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

        // Phase 5: Start ProotService and launch Wayland
        statusText.text = getString(R.string.starting_desktop)
        ProotService.start(this)

        // Wait for Wayland socket to appear (up to 15 seconds)
        withContext(Dispatchers.IO) {
            val socketFile = File(filesDir, "usr/tmp/wayland-0")
            var attempts = 0
            while (!socketFile.exists() && attempts < 30) {
                Thread.sleep(500)
                attempts++
            }
        }

        try {
            val waylandIntent = Intent(this, Class.forName("com.termux.x11.MainActivity"))
            startActivity(waylandIntent)
        } catch (e: Exception) {
            // WaylandActivity may not be ready yet — show status instead
            statusText.text = "Desktop starting... Check notification."
        }
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
    }
}
