package com.nemesis.apk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutomationBootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent?){
        if(intent?.action!=Intent.ACTION_BOOT_COMPLETED)return
        AutomationScheduler.rescheduleAll(context)
        if(NemesisService.isEnabled(context)) try{context.startForegroundService(Intent(context,NemesisService::class.java))}
        catch(e:Exception){android.util.Log.w("NEMESIS-AUTO","Service NEMESIS différé après démarrage",e)}
    }
}
