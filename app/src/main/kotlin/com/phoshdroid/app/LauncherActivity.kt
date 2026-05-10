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

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* proceed regardless */ }

    // The running proot session's XLORIE dimensions are persisted in
    // SharedPreferences. onCreate compares CURRENT display to LAST SAVED
    // dimensions; if they differ (e.g. user folded the phone between launches)
    // we kill the process and relaunch so the new proot run picks up the
    // current metrics. Persisted state survives process death.
    private var restartInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ACTION_SETTINGS) {
            setContentView(R.layout.activity_launcher)
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
            return
        }

        setContentView(R.layout.activity_launcher)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        errorText = findViewById(R.id.errorText)

        if (checkForDisplayShift("onCreate")) return

        // Phosh is already running behind MainActivity from a previous
        // bootstrap in this process. Re-tapping the launcher icon lands here;
        // forward the user to MainActivity instead of sitting on the loading
        // screen. Re-running bootstrap would wipe the live X server socket
        // and start a second X server on :0.
        if (bootstrapCompleted) {
            android.util.Log.i("Phoshdroid", "onCreate: already bootstrapped, forwarding to MainActivity")
            forwardToMainActivity()
            return
        }

        // A previous LauncherActivity instance in this process already kicked
        // off bootstrap — it's suspended in waitForPhoshReady or still
        // extracting. Starting a second one would tear down the first's X11
        // socket mid-setup and leave phosh half-initialised. Show the loading
        // screen and let the in-flight bootstrap finish.
        if (bootstrapInProgress) {
            android.util.Log.i("Phoshdroid", "onCreate: bootstrap already in progress, waiting")
            return
        }

        bootstrapInProgress = true
        requestNotificationPermission()
        lifecycleScope.launch {
            try {
                bootstrap()
                bootstrapCompleted = true
            } catch (e: Exception) {
                showError("Startup failed: ${e.message}\n\n${e.stackTraceToString()}", copyable = true)
            } finally {
                bootstrapInProgress = false
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The launcher icon (or the FGS notification) was tapped while this
        // LauncherActivity was already alive in the background. If bootstrap
        // finished while we were away, forward to MainActivity now — the
        // earlier in-bootstrap attempt would have been BAL-blocked.
        setIntent(intent)
        if (bootstrapCompleted && !restartInFlight) {
            android.util.Log.i("Phoshdroid", "onNewIntent: forwarding to MainActivity")
            forwardToMainActivity()
        }
    }

    override fun onResume() {
        super.onResume()
        // Catches the case where bootstrap completed while we were paused
        // (Android backed our startActivity call out as BAL_BLOCK). The
        // user is now back in front of LauncherActivity — hand off to
        // MainActivity, which is allowed because we now have a visible
        // activity to launch from.
        if (bootstrapCompleted && !restartInFlight && !isFinishing) {
            android.util.Log.i("Phoshdroid", "onResume: bootstrap done, forwarding to MainActivity")
            forwardToMainActivity()
        }
    }

    // onConfigurationChanged / onResume / DisplayListener intentionally do NOT
    // trigger shift detection. During activity startup Android can report
    // transient dimensions (splash-time config vs final config), and any check
    // that fires mid-bootstrap will see that transition as a "shift" and kill
    // the process — causing a restart loop. The single onCreate check at the
    // start of each process is enough: a genuine fold/unfold between launches
    // shows up there. Fold during active use is not auto-handled; the user
    // relaunches the app to pick up the new dimensions.

    private fun currentDisplayId(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.displayId ?: 0
        }

    /**
     * Compares the activity's current display to whatever the running proot
     * session was started against. If they differ, persists the new dims and
     * tears down the process. Returns true if a restart was initiated.
     *
     * Uses WindowManager.currentWindowMetrics instead of resources.displayMetrics
     * because the latter can return stale values during onCreate before Android
     * has finished propagating the activity's final configuration — which would
     * otherwise cause false-positive shifts on subsequent lifecycle callbacks.
     */
    private fun checkForDisplayShift(source: String): Boolean {
        if (restartInFlight) return true
        val bounds = windowManager.currentWindowMetrics.bounds
        val currentW = bounds.width()
        val currentH = bounds.height()
        val currentId = currentDisplayId()
        val prefs = getSharedPreferences(DISPLAY_STATE_PREFS, MODE_PRIVATE)
        val lastW = prefs.getInt("last_w", 0)
        val lastH = prefs.getInt("last_h", 0)
        val lastId = prefs.getInt("last_id", -1)

        if (lastW != 0 && (lastW != currentW || lastH != currentH || lastId != currentId)) {
            android.util.Log.w(
                "Phoshdroid",
                "[$source] display shift ${lastW}x$lastH@$lastId -> ${currentW}x$currentH@$currentId, restarting",
            )
            prefs
                .edit()
                .putInt("last_w", currentW)
                .putInt("last_h", currentH)
                .putInt("last_id", currentId)
                .commit() // sync so the restarted process sees the new values
            restartInFlight = true
            restartApp()
            return true
        }
        // First run or same dimensions — record and move on.
        prefs
            .edit()
            .putInt("last_w", currentW)
            .putInt("last_h", currentH)
            .putInt("last_id", currentId)
            .apply()
        return false
    }

    private fun forwardToMainActivity() {
        try {
            val waylandIntent =
                Intent(this, Class.forName("com.termux.x11.MainActivity"))
                    .putExtra("phoshdroid_top_padding_px", TOP_GAP_PX)
                    .putExtra("phoshdroid_bottom_padding_px", BOTTOM_GAP_PX)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(waylandIntent)
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (e: Exception) {
            android.util.Log.e("Phoshdroid", "Failed to forward to MainActivity", e)
            statusText.text = "Display failed: ${e.message}"
        }
    }

    private fun restartApp() {
        try {
            com.phoshdroid.app.proot.ProotService
                .stop(this)
        } catch (_: Exception) {
        }
        val restartIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (restartIntent != null) {
            restartIntent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK,
            )
            val pending =
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    restartIntent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            val am = getSystemService(android.app.AlarmManager::class.java)
            am?.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 200, pending)
        }
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestAllFilesAccess()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (android.os.Environment.isExternalStorageManager()) return
        try {
            val intent =
                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(android.net.Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (_: Exception) {
            // Fallback: generic Settings entry for MANAGE_EXTERNAL_STORAGE
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
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
            val extracted =
                withContext(Dispatchers.IO) {
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

        // Phase 2: Extract pmOS rootfs directly into proot's expected location.
        // The previous two-phase "extract to files/rootfs/, then rename-move
        // into files/usr/var/lib/.../postmarketos/" layout was fragile — if
        // the process was killed between extraction and rename (display-shift
        // restart, OOM kill, etc.), subsequent launches found rootfsDir
        // already present, skipped the move, and left a half-populated rootfs
        // that hung phosh at "Preparing your desktop".
        val prefixDir = "$filesDir/usr"
        val rootfsDir = File(prefixDir, "var/lib/proot-distro/installed-rootfs/${ProotService.DISTRO_NAME}")
        if (!File(rootfsDir, ".extraction_complete").exists()) {
            showProgress(getString(R.string.extracting_rootfs))

            val availableBytes = StatFs(filesDir.absolutePath).availableBytes
            val requiredBytes = 1_500_000_000L
            if (availableBytes < requiredBytes) {
                showError(
                    getString(
                        R.string.error_no_space,
                        formatSize(requiredBytes),
                        formatSize(availableBytes),
                    ),
                )
                return
            }

            progressBar.isIndeterminate = true
            rootfsDir.mkdirs()
            // Clean up any leftover staging dir from older APK versions that
            // used the two-phase layout, so we reclaim that disk space.
            withContext(Dispatchers.IO) {
                File(filesDir, "rootfs").deleteRecursively()
            }
            val extracted =
                withContext(Dispatchers.IO) {
                    assets.open("rootfs.bin").use { input ->
                        extractor.extract(input, -1, rootfsDir)
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
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
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
            File(tmpDir, "dbus-session"),
            // Stale start log from prior session: waitForPhoshReady below
            // would otherwise see the previous run's "Phosh ready after"
            // line and return true instantly, reintroducing the empty-X11
            // flash we're trying to mask.
            File(tmpDir, "phoshdroid-start.log"),
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
                android.util.Log.e("Phoshdroid", "XLORIE geometry: ${dw}x$dh")
            }
            android.util.Log.e("Phoshdroid", "=== Phase 5: About to start X11 server ===")

            // First test: can we even load the CmdEntryPoint class?
            val cls = Class.forName("com.termux.x11.CmdEntryPoint")
            android.util.Log.e("Phoshdroid", "CmdEntryPoint class loaded OK: ${cls.name}")

            // Start X11 server with full constructor + Looper (needed for work queue)
            Thread({
                android.os.Looper.prepare()
                try {
                    val ctor =
                        com.termux.x11.CmdEntryPoint::class.java
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

        // Start proot first so phoc + phosh start coming up while the user
        // still sees this loading screen (logo + "Starting desktop…").
        android.util.Log.e("Phoshdroid", "Starting ProotService...")
        ProotService.start(this)
        statusText.text = getString(R.string.starting_desktop)

        // Wait for phosh to actually be rendering before we hand control to
        // MainActivity. Otherwise the Termux:X11 view paints its empty root
        // with the default cursor for 10–30s on cold start — users think
        // something has hung. We watch the proot-side start log for phosh's
        // own "Phosh ready after" line.
        val phoshReady = waitForPhoshReady(File(prefixDir, "tmp/phoshdroid-start.log"))
        if (!phoshReady) {
            android.util.Log.w("Phoshdroid", "Phosh-ready timeout, launching MainActivity anyway")
        }

        android.util.Log.e("Phoshdroid", "Launching X11 activity...")
        // If the user backgrounded us during bootstrap, Android 14+ BAL
        // policy blocks startActivity from a non-visible activity even with
        // an FGS running. Skip the doomed attempt; onResume / onNewIntent
        // (or the FGS notification tap) will retry from a foreground
        // context that BAL allows.
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            android.util.Log.i(
                "Phoshdroid",
                "bootstrap finished while backgrounded; deferring MainActivity launch",
            )
            return
        }
        try {
            val waylandIntent =
                Intent(this, Class.forName("com.termux.x11.MainActivity"))
                    .putExtra("phoshdroid_top_padding_px", TOP_GAP_PX)
                    .putExtra("phoshdroid_bottom_padding_px", BOTTOM_GAP_PX)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(waylandIntent)
            // LauncherActivity is no longer useful — let the user back-swipe
            // straight out of MainActivity instead of returning here.
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (e: Exception) {
            statusText.text = "Display failed: ${e.message}"
        }
    }

    /**
     * Poll the proot startup log for phosh's own "Phosh ready after N.Ns"
     * line. Returns true when it appears, false on timeout.
     */
    private suspend fun waitForPhoshReady(
        logFile: File,
        // Bumped from 60s after observing a 2m30s phosh startup on a Pixel 9
        // Pro Fold under proot — the chown -R user:user /home/user step in
        // start.sh dominated the time. 60s caused a premature MainActivity
        // launch into an empty X root; the LorieView never repainted and the
        // user saw the X-cursor desktop indefinitely.
        timeoutMs: Long = 240_000,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (logFile.exists()) {
                    try {
                        if (logFile.bufferedReader().useLines { lines ->
                                lines.any { it.contains("Phosh ready after") }
                            }
                        ) {
                            return@withContext true
                        }
                    } catch (_: Exception) {
                        // log being actively written — retry
                    }
                }
                Thread.sleep(500)
            }
            false
        }

    private fun showProgress(message: String) {
        statusText.text = message
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        errorText.visibility = View.GONE
    }

    private fun showError(
        message: String,
        copyable: Boolean = false,
    ) {
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
        private const val DISPLAY_STATE_PREFS = "phoshdroid_display_state"

        // Per-process flag: true once bootstrap() has run to completion.
        // Resets when the process is killed (fold/unfold restarts the whole
        // process via AlarmManager).
        @Volatile
        private var bootstrapCompleted = false

        // Per-process flag: set while bootstrap() is actively running so a
        // second onCreate in the same process (e.g. user re-tapping the icon
        // while the first bootstrap is still in waitForPhoshReady) doesn't
        // kick off a concurrent second bootstrap and wipe the first's sockets.
        @Volatile
        private var bootstrapInProgress = false
    }
}
