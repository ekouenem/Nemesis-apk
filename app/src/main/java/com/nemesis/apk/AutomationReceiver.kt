package com.nemesis.apk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutomationReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent?){
        val id=intent?.getStringExtra("automation_id")?.takeIf{it.isNotBlank()}?:return
        try{context.startForegroundService(Intent(context,AutomationExecutionService::class.java).putExtra("automation_id",id))}
        catch(e:Exception){android.util.Log.w("NEMESIS-AUTO","Android différera l’automatisation",e)}
    }
}
