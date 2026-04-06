package com.phoshdroid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class PhoshdroidApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phoshdroid Service",
            NotificationManager.IMPORTANCE_LOW
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
