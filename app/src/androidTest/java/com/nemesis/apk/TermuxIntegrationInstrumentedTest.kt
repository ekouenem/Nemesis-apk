package com.nemesis.apk

import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.nemap.android.EmbeddedRuntime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class TermuxIntegrationInstrumentedTest {
 private fun waitUntil(message:String, condition:()->Boolean) {
  val end=System.currentTimeMillis()+15000
  while(System.currentTimeMillis()<end) { if(condition()) return; Thread.sleep(150) }
  assertTrue(message,condition())
 }
 private fun connectable(port:Int):Boolean = try { Socket().use { it.connect(InetSocketAddress("127.0.0.1",port),400) }; true } catch(_:Exception) { false }
 @Test fun notificationCloseStopsAndOpeningRestarts() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  try {
   val nm=ctx.getSystemService(NotificationManager::class.java)
   waitUntil("notification absente") { nm.activeNotifications.any { it.id==4101 } && EmbeddedRuntime.enabled }
   val port=EmbeddedRuntime.port(); assertTrue(connectable(port))
   val n=nm.activeNotifications.first { it.id==4101 }.notification
   val close=n.actions.firstOrNull { it.title.toString()=="Fermer" } ?: throw AssertionError("Action Fermer absente")
   inst.runOnMainSync { a.moveTaskToBack(true) }; inst.waitForIdleSync()
   close.actionIntent.send()
   waitUntil("NemApi doit être arrêté") { !EmbeddedRuntime.enabled && !NemesisService.isEnabled(ctx) && nm.activeNotifications.none { it.id==4101 } }
   assertFalse("port fermé",connectable(port))
   assertNull("redémarrage périodique annulé",ctx.getSystemService(JobScheduler::class.java).getPendingJob(RuntimeRecoveryJob.ID))
   val resume=Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
   ctx.startActivity(resume)
   waitUntil("NemApi doit redémarrer à l’ouverture") { EmbeddedRuntime.enabled && NemesisService.isEnabled(ctx) && nm.activeNotifications.any { it.id==4101 } }
   assertTrue(connectable(EmbeddedRuntime.port()))
  } finally { a.finish() }
 }
 @Test fun realTermuxSshCommandAndSftp() {
  val args=InstrumentationRegistry.getArguments()
  assumeTrue(args.getString("termux_real")=="true")
  val password=args.getString("termux_password") ?: throw AssertionError("Mot de passe du test absent")
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync()
  val prefs=ctx.getSharedPreferences("ssh_executor",Context.MODE_PRIVATE); val saved=prefs.all.toMap()
  val m=Python.getInstance().getModule("mobile_agent"); val json=Python.getInstance().getModule("json")
  var sid:String?=null
  val remote="/data/data/com.termux/files/home/nemesis-integration-test.txt"
  fun tool(name:String, params:JSONObject, id:String?=null):JSONObject = JSONObject(m.callAttr("_tool",name,json.callAttr("loads",params.toString()),id).toString())
  try {
   SshBridge.init(ctx); SshBridge.save("127.0.0.1",8022,"nemesis","",password)
   val r=tool("bash",JSONObject().put("command","printf 'integration-termux-ok' > '$remote'; cat '$remote'"))
   assertTrue(r.toString(),r.optBoolean("success")); assertEquals("integration-termux-ok",r.optString("output"))
   assertTrue("empreinte TOFU mémorisée",SshBridge.details().optString("fingerprint").startsWith("SHA256:"))
   sid=JSONObject(m.callAttr("create_session","code").toString()).getString("id")
   val dl=tool("download_file",JSONObject().put("remote_path",remote).put("path","termux-test.txt"),sid)
   assertTrue(dl.toString(),dl.optBoolean("success"))
   val directory=File(m.callAttr("workspace_root",sid).toString())
   assertEquals("integration-termux-ok",File(directory,"termux-test.txt").readText())
   File(directory,"termux-test.txt").delete(); directory.delete()
   val cleanup=tool("bash",JSONObject().put("command","rm -f '$remote'")); assertTrue(cleanup.toString(),cleanup.optBoolean("success"))
  } finally {
   if(sid!=null) m.callAttr("delete_session",sid)
   val e=prefs.edit().clear(); for((k,v) in saved) when(v) { is String -> e.putString(k,v); is Int -> e.putInt(k,v); is Boolean -> e.putBoolean(k,v); is Long -> e.putLong(k,v); is Float -> e.putFloat(k,v) }; e.commit()
   a.finish()
  }
 }
}
