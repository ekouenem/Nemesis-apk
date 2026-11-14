package com.nemesis.apk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Posts a low-detail completion ping only when the user has left NEMESIS. */
object AppTaskNotifications {
    @Volatile var foreground: Boolean = false
    private const val CHANNEL="nemesis_task_results"
    @JvmStatic fun completed(context:Context,sessionId:String,success:Boolean) {
        if(foreground)return
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        try {
            val manager=context.getSystemService(NotificationManager::class.java)
            if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel(CHANNEL,"Réponses NEMESIS",NotificationManager.IMPORTANCE_DEFAULT))
            val open=Intent(context,MainActivity::class.java).putExtra(AutomationExecutionService.EXTRA_SESSION,sessionId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi=PendingIntent.getActivity(context,sessionId.hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val title=if(success)"NEMESIS a terminé" else "NEMESIS a rencontré un problème"
            val text=if(success)"Votre réponse est prête. Touchez pour ouvrir la discussion." else "La tâche n’a pas abouti. Touchez pour vérifier la discussion."
            val builder=if(Build.VERSION.SDK_INT>=26)android.app.Notification.Builder(context,CHANNEL) else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
            val notification=builder.setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(pi).build()
            manager.notify(6100+(sessionId.hashCode() and 0x0000ffff),notification)
        }catch(e:Exception){android.util.Log.w("NEMESIS-NOTIFY","Notification de fin indisponible",e)}
    }
}
