package com.nemesis.apk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Process priority while the embedded provider is available; Android may still stop it. */
class NemesisService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nemesis_runtime", "NEMESIS actif", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "nemesis_runtime").setContentTitle("NEMESIS actif")
            .setContentText("NemApi est disponible pour les conversations et les tâches.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setOngoing(true).setContentIntent(open).build()
        startForeground(4101, n)
        return START_STICKY
    }
}
