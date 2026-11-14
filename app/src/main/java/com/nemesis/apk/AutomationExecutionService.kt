package com.nemesis.apk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import com.chaquo.python.Python
import com.nemap.android.web.WebViewHost
import org.json.JSONObject
import java.util.concurrent.Executors

/** Executes an automation in its owning session, never by creating a new chat. */
class AutomationExecutionService:Service(){
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val id=intent?.getStringExtra("automation_id")?.takeIf{it.isNotBlank()}
        if(id==null){stopSelf(startId);return START_NOT_STICKY}
        ensureChannel()
        startForeground(4301,notification("Automatisation en cours","NEMESIS exécute la tâche dans sa discussion.",null,false))
        try{if(!WebViewHost.isCreated)WebViewHost.ensure(applicationContext)}catch(e:Exception){android.util.Log.w("NEMESIS-AUTO","WebView différée",e)}
        worker.execute{runAutomation(id,startId)}
        return START_NOT_STICKY
    }
    private fun runAutomation(id:String,startId:Int){
        try{
            AgentRuntimeBootstrap.initialize(applicationContext)
            val module=Python.getInstance().getModule("mobile_agent")
            val due=JSONObject(module.callAttr("automation_due",id).toString())
            if(!due.optBoolean("success")||!due.optBoolean("due")){
                finishService(startId);return
            }
            val sid=due.optString("session_id");val title=due.optString("title","Automatisation")
            val prompt="[Automatisation programmée : $title]\n\n"+due.optString("prompt")
            main.post{
                val started=JobRunner.start(applicationContext,sid,prompt,"[]","Automatisation · $title",notifyCompletion=false) { ok,error ->
                    worker.execute{
                        var detail=error.orEmpty()
                        try{
                            val completed=JSONObject(module.callAttr("automation_complete",id,ok,detail).toString())
                            if(!completed.optBoolean("success"))detail=completed.optString("error",detail)
                        }catch(e:Exception){detail="Enregistrement du résultat impossible"}
                        showResult(id,title,sid,ok,detail)
                        stopMain(startId)
                    }
                }
                if(!started){
                    worker.execute{
                        try{module.callAttr("automation_snooze",id,300)}catch(_:Exception){}
                        stopMain(startId)
                    }
                }
            }
        }catch(e:Exception){
            android.util.Log.e("NEMESIS-AUTO","Échec d’automatisation",e)
            try{val module=Python.getInstance().getModule("mobile_agent");module.callAttr("automation_complete",id,false,"Service d’automatisation indisponible") }catch(_:Exception){}
            showResult(id,"Automatisation",null,false,"Impossible d’exécuter la tâche. Consultez la discussion.")
            finishService(startId)
        }
    }
    private fun ensureChannel(){
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Automatisations NEMESIS",NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun notification(title:String,text:String,session:String?,ongoing:Boolean):Notification{
        val open=Intent(this,MainActivity::class.java).apply{session?.let{putExtra(EXTRA_SESSION,it)};addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)}
        val pi=PendingIntent.getActivity(this,session?.hashCode()?:4301,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).setContentText(text).setOngoing(ongoing).setAutoCancel(!ongoing).setContentIntent(pi).build()
    }
    private fun showResult(id:String,title:String,sid:String?,ok:Boolean,detail:String){
        ensureChannel()
        val text=if(ok)"Tâche terminée · résultat dans la discussion d’origine" else "Échec · ouvrez la discussion pour vérifier"
        getSystemService(NotificationManager::class.java).notify(AutomationSchedulerNotification.id(id),notification(if(ok)title else "Automatisation à vérifier",text,sid,false))
    }
    private fun stopMain(startId:Int){main.post{stopForeground(true);stopSelf(startId)}}
    private fun finishService(startId:Int)=stopMain(startId)
    override fun onDestroy(){worker.shutdown();super.onDestroy()}
    companion object{
        const val EXTRA_SESSION="nemesis_open_session"
        private const val CHANNEL="nemesis_automations"
    }
}

object AutomationSchedulerNotification { fun id(id:String)=4302+(id.hashCode() and 0x0000ffff) }
