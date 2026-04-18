package com.phoshdroid.app.proot

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phoshdroid.app.LauncherActivity
import com.phoshdroid.app.PhoshdroidApp
import com.phoshdroid.app.R
import com.phoshdroid.app.settings.PhoshdroidPreferences
import java.io.File

class ProotService : Service() {

    private var prootProcess: Process? = null
    private var manager: ProotDistroManager? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val filesDir = applicationContext.filesDir
        val nativeLibDir = applicationInfo.nativeLibraryDir
        android.util.Log.e("ProotService", "nativeLibDir=$nativeLibDir")
        val prefixDir = "${filesDir}/usr"
        val commandBuilder = ProotCommandBuilder(
            nativeLibDir = nativeLibDir,
            prefixDir = prefixDir,
            distroName = DISTRO_NAME
        )
        manager = ProotDistroManager(
            commandBuilder = commandBuilder,
            installedRootfsDir = File(prefixDir, "var/lib/proot-distro/installed-rootfs"),
            rootfsTarball = File(filesDir, "rootfs/rootfs.tar")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProot()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startProot()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        stopProot()
        super.onDestroy()
    }

    private fun startProot() {
        if (prootProcess != null) return

        val prefs = PhoshdroidPreferences(this)
        val startupScript = prefs.customStartupScript ?: DEFAULT_STARTUP_SCRIPT

        android.util.Log.i("ProotService", "Starting proot with script: $startupScript")
        prootProcess = manager?.login(
            startupScript = startupScript,
            bindSdcard = prefs.bindSdcard
        )

        Thread {
            // Capture proot stdout/stderr
            val output = prootProcess?.inputStream?.bufferedReader()?.readText() ?: ""
            val exitCode = prootProcess?.waitFor() ?: -1
            android.util.Log.i("ProotService", "proot exited ($exitCode): $output")

            // Write to a file for debugging
            try {
                java.io.File(applicationContext.filesDir, "usr/tmp/proot-exit.log").writeText(
                    "exit=$exitCode\n$output"
                )
            } catch (_: Exception) {}

            if (exitCode != 0) {
                prootProcess = manager?.login(
                    startupScript = startupScript,
                    bindSdcard = prefs.bindSdcard
                )
                val retryOutput = prootProcess?.inputStream?.bufferedReader()?.readText() ?: ""
                val retryCode = prootProcess?.waitFor() ?: -1
                android.util.Log.i("ProotService", "proot retry exited ($retryCode): $retryOutput")
                if (retryCode != 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.start()
    }

    private fun stopProot() {
        prootProcess?.let { proc ->
            proc.destroy()
            proc.waitFor()
        }
        prootProcess = null
    }

    private fun buildNotification(): Notification {
        val settingsIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, LauncherActivity::class.java).apply {
                action = LauncherActivity.ACTION_SETTINGS
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ProotService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PhoshdroidApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_settings), settingsIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.phoshdroid.app.STOP"
        const val DISTRO_NAME = "postmarketos"
        const val DEFAULT_STARTUP_SCRIPT = "/etc/phoshdroid/start.sh"

        /**
         * True while the foreground service is alive in this process. Used by
         * LauncherActivity to skip bootstrap on activity recreation (Android
         * re-runs onCreate without destroying the process) so we don't spin up
         * a second X11 server on display :0.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ProotService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProotService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
