package com.phoshdroid.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Detects when the device's display dimensions change between Phoshdroid
 * foregrounding events and restarts the process so the new proot session is
 * launched against the current metrics.
 *
 * Why a process-level monitor and not just LauncherActivity:
 *
 * After bootstrap, the user is in com.termux.x11.MainActivity (the LorieView
 * surface), not LauncherActivity. The X server, phoc and phosh were all sized
 * to whatever XLORIE_WIDTH/HEIGHT the launcher chose at boot. Folding /
 * unfolding / external display attach changes Android's display metrics, but
 * MainActivity has no Phoshdroid-side hook to react — the live phosh session
 * keeps rendering at the old size, leaving black borders or letterboxing.
 *
 * Hooking [Application.ActivityLifecycleCallbacks] catches a return-to-
 * foreground for *any* activity in our app, including MainActivity. We
 * fire a check only on the false→true transition of "any activity started"
 * to avoid false positives from internal activity transitions (Launcher →
 * MainActivity), Android-driven configChanges, or splash-time dim flicker.
 *
 * Persisted state: [PREFS] / "last_w"/"last_h"/"last_id". Same keys
 * LauncherActivity uses, so first-launch and post-restart paths share one
 * source of truth.
 */
class DisplayShiftMonitor(
    private val app: Application,
) : Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0

    fun register() {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val wasInBackground = startedActivities == 0
        startedActivities++
        if (wasInBackground) {
            // App just came to foreground (from cold start, recents, or
            // notification tap). Sample current display metrics and compare
            // to what the running proot session was launched against.
            checkAndMaybeRestart(activity, source = activity.javaClass.simpleName)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }

    // No-op overrides
    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}

    override fun onActivityDestroyed(activity: Activity) {}

    private fun checkAndMaybeRestart(
        activity: Activity,
        source: String,
    ) {
        // Skip while bootstrap is still running. LauncherActivity's own
        // onCreate covers the cold-start case; firing here before the
        // first proot session is up could create a restart loop on
        // devices that report transient dimensions during the splash →
        // final-config transition.
        if (LauncherActivity.bootstrapInProgressForMonitor()) {
            Log.d("Phoshdroid.Monitor", "[$source] bootstrap in progress, skipping")
            return
        }
        val bounds = activity.windowManager.currentWindowMetrics.bounds
        val currentW = bounds.width()
        val currentH = bounds.height()
        // Defensively skip when the WM hasn't given us real dimensions yet.
        // The next foregrounding tick will catch it.
        if (currentW <= 0 || currentH <= 0) {
            Log.d("Phoshdroid.Monitor", "[$source] no metrics yet, skipping")
            return
        }
        val currentId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display?.displayId ?: 0
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay?.displayId ?: 0
            }

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastW = prefs.getInt("last_w", 0)
        val lastH = prefs.getInt("last_h", 0)
        val lastId = prefs.getInt("last_id", -1)

        // First run: just record. LauncherActivity will pick these up when it
        // sets XLORIE_WIDTH/HEIGHT for the first proot launch.
        if (lastW == 0) {
            prefs
                .edit()
                .putInt("last_w", currentW)
                .putInt("last_h", currentH)
                .putInt("last_id", currentId)
                .apply()
            return
        }

        if (lastW == currentW && lastH == currentH && lastId == currentId) {
            // Unchanged — nothing to do.
            return
        }

        // Genuine shift. Persist new dims (commit so the restarted process
        // reads them on first boot) and kill ourselves; AlarmManager brings
        // us back in 200 ms.
        Log.w(
            "Phoshdroid.Monitor",
            "[$source] display shift ${lastW}x$lastH@$lastId -> ${currentW}x$currentH@$currentId, restarting",
        )
        prefs
            .edit()
            .putInt("last_w", currentW)
            .putInt("last_h", currentH)
            .putInt("last_id", currentId)
            .commit()

        // Clear the per-process bootstrap flags so the restarted process
        // re-runs bootstrap against the new metrics. They live as static
        // companion fields on LauncherActivity but the process-kill below
        // resets them anyway; mentioning here for the reader.

        try {
            // Stop proot cleanly first; saves a stale rootfs lock and gives
            // FGS a chance to tear down before the process dies.
            com.phoshdroid.app.proot.ProotService
                .stop(app)
        } catch (_: Exception) {
        }

        val restartIntent =
            app.packageManager
                .getLaunchIntentForPackage(app.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (restartIntent != null) {
            val pending =
                PendingIntent.getActivity(
                    app,
                    0,
                    restartIntent,
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val am = app.getSystemService(AlarmManager::class.java)
            am?.set(AlarmManager.RTC, System.currentTimeMillis() + 200, pending)
        }

        // ActivityManager.killBackgroundProcesses won't kill a foreground
        // service host, so use the kill-self path the launcher already used.
        activity.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        const val PREFS = "phoshdroid_display_state"
    }
}
