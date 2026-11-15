package com.nemesis.apk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.nemap.android.EmbeddedRuntime

/** Process priority while the embedded provider is available; Android may still stop it. */
class NemesisService : Service() {
    companion object {
        const val ACTION_CLOSE = "com.nemesis.apk.CLOSE_RUNTIME"
        private const val PREFS = "runtime-control"
        private const val ENABLED = "enabled"
        fun isEnabled(context: Context) = context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(ENABLED,true)
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(ENABLED,enabled).commit()
        }
    }
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdog=object:Runnable {
        override fun run() {
            if (!isEnabled(applicationContext)) return
            try { EmbeddedRuntime.start(applicationContext) }
            catch(e:Throwable) { android.util.Log.e("NEMESIS", "NemApi indisponible, nouvelle tentative",e) }
            handler.postDelayed(this,15_000)
        }
    }
    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        com.nemap.android.core.AppState.onSecurityChallenge = null
        super.onDestroy()
    }

    /**
     * Un refus de vérification de sécurité du provider est un événement rare
     * qui mérite une action VISIBLE : l'utilisateur ouvre l'application et
     * vérifie la session. Aucun renvoi automatique n'est déclenché — c'est
     * précisément ce qui protège le compte.
     */
    private fun installChallengeHook() {
        com.nemap.android.core.AppState.onSecurityChallenge = {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("nemesis_alerts", "Alertes DeepSeek", NotificationManager.IMPORTANCE_HIGH))
                val pi = PendingIntent.getActivity(
                    this, 2,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alert = Notification.Builder(this, "nemesis_alerts")
                    .setContentTitle("DeepSeek : vérification de sécurité")
                    .setContentText("Ouvre NEMESIS pour vérifier la session. Aucun renvoi ne partira pendant le refroidissement.")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                nm.notify(4102, alert)
            } catch (_: Throwable) {
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            setEnabled(applicationContext,false)
            RuntimeRecoveryJob.cancel(applicationContext)
            handler.removeCallbacks(watchdog)
            EmbeddedRuntime.stop()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isEnabled(applicationContext)) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nemesis_runtime", "NEMESIS actif", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 1, Intent(this,NemesisService::class.java).setAction(ACTION_CLOSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "nemesis_runtime").setContentTitle("NEMESIS actif")
            .setContentText("NemApi est disponible pour les conversations et les tâches.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null,"Fermer",close).build()).build()
        startForeground(4101, n)
        installChallengeHook()
        RuntimeRecoveryJob.schedule(applicationContext)
        handler.removeCallbacks(watchdog); handler.post(watchdog)
        return START_STICKY
    }
}
