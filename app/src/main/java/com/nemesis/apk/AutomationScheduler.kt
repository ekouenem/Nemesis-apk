package com.nemesis.apk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Exact, reboot-rescheduled triggers. The database remains canonical; prefs keep alarm metadata only. */
object AutomationScheduler {
    private const val PREFS="automation-alarms"
    @Volatile private var app:Context?=null
    @JvmStatic fun init(context:Context){app=context.applicationContext}

    @JvmStatic fun schedule(id:String, atMillis:Long, repeat:String):Boolean {
        val context=app?:return false
        if(id.isBlank() || repeat !in setOf("none","daily","weekly")) return false
        return try {
            val intent=Intent(context,AutomationReceiver::class.java).setAction("com.nemesis.apk.AUTOMATION_DUE").putExtra("automation_id",id)
            val pending=PendingIntent.getBroadcast(context,requestCode(id),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val alarm=context.getSystemService(AlarmManager::class.java)
            try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,atMillis,pending) }
            catch (_:SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,atMillis,pending) }
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(id,JSONObject().put("at",atMillis).put("repeat",repeat).toString()).apply()
            true
        } catch (_:Exception){false}
    }

    @JvmStatic fun cancel(id:String):Boolean {
        val context=app?:return false
        return try {
            val intent=Intent(context,AutomationReceiver::class.java).setAction("com.nemesis.apk.AUTOMATION_DUE").putExtra("automation_id",id)
            val pending=PendingIntent.getBroadcast(context,requestCode(id),intent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if(pending!=null){context.getSystemService(AlarmManager::class.java).cancel(pending);pending.cancel()}
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(id).apply();true
        }catch(_:Exception){false}
    }

    @JvmStatic fun nextOccurrenceMillis(previousMillis:Long, repeat:String):Long {
        if(repeat !in setOf("daily","weekly")) return 0L
        val zone=ZoneId.systemDefault();var next=Instant.ofEpochMilli(previousMillis).atZone(zone)
        val now=System.currentTimeMillis()
        do { next=if(repeat=="daily") next.plusDays(1) else next.plusWeeks(1) }
        while(next.toInstant().toEpochMilli()<=now)
        return next.toInstant().toEpochMilli()
    }

    @JvmStatic fun rescheduleAll(context:Context){
        init(context)
        val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        for((id,raw) in prefs.all){
            if(raw !is String) continue
            try {
                val row=JSONObject(raw);val at=row.optLong("at",0L);val repeat=row.optString("repeat","none")
                if(at>0 && repeat in setOf("none","daily","weekly")) schedule(id,if(at<=System.currentTimeMillis()) System.currentTimeMillis()+5_000L else at,repeat)
            }catch(_:Exception){cancel(id)}
        }
    }
    private fun requestCode(id:String)=id.hashCode() and 0x7fffffff
}
