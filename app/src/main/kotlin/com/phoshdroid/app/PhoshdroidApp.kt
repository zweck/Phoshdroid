package com.phoshdroid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class PhoshdroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Process-wide hook — fires once when the app foregrounds (cold
        // start, recents, notification-tap, fold/unfold) regardless of
        // which activity is on top. Lets us recalibrate the X11 surface
        // size from MainActivity, where LauncherActivity's own onResume
        // would never fire.
        DisplayShiftMonitor(this).register()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Phoshdroid Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when the postmarketOS desktop is running"
            }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "phoshdroid_service"
    }
}
