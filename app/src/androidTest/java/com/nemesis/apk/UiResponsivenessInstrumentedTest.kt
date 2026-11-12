package com.nemesis.apk

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local regression: do not use a provider, account or network. */
@RunWith(AndroidJUnit4::class)
class UiResponsivenessInstrumentedTest {
 private fun children(v:View):List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
 @Test fun slowObservationMustNotFreezeTheUiWhileStreaming() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync(); val py=Python.getInstance(); val m=py.getModule("mobile_agent")
  val id=JSONObject(m.callAttr("create_session","chat").toString()).getString("id")
  val globals=py.builtins.callAttr("dict")
  val busy=JobRunner::class.java.getDeclaredField("busySession").apply { isAccessible=true }
  val show=MainActivity::class.java.getDeclaredMethod("showChat",String::class.java).apply { isAccessible=true }
  try {
   m.callAttr("_message",id,"assistant","Réponse locale")
   busy.set(JobRunner,id); inst.runOnMainSync { show.invoke(a,id) }; awaitChat(a)
   py.builtins.callAttr("exec", "import mobile_agent, time\nmobile_agent._original_revision = mobile_agent.message_revision\ndef slow_revision(sid):\n time.sleep(4)\n return mobile_agent._original_revision(sid)\nmobile_agent.message_revision = slow_revision", globals)
   Thread.sleep(900)
   val heartbeat=CountDownLatch(1); Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
   assertTrue("Le fil UI ne doit pas attendre une observation lente",heartbeat.await(1200,TimeUnit.MILLISECONDS))
  } finally {
   py.builtins.callAttr("exec", "import mobile_agent\nmobile_agent.message_revision = mobile_agent._original_revision", globals)
   busy.set(JobRunner,null); m.callAttr("delete_session",id); a.finish()
  }
 }
 @Test fun slowSessionLoadMustNotFreezeTheUi() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync(); val py=Python.getInstance(); val m=py.getModule("mobile_agent"); val globals=py.builtins.callAttr("dict")
  val id=JSONObject(m.callAttr("create_session","chat").toString()).getString("id")
  val show=MainActivity::class.java.getDeclaredMethod("showChat",String::class.java).apply { isAccessible=true }
  try {
   py.builtins.callAttr("exec", "import mobile_agent, time\nmobile_agent._original_get_session = mobile_agent.get_session\ndef slow_session(sid, *args):\n time.sleep(4)\n return mobile_agent._original_get_session(sid, *args)\nmobile_agent.get_session = slow_session", globals)
   Handler(Looper.getMainLooper()).post { show.invoke(a,id) }
   Thread.sleep(700)
   val heartbeat=CountDownLatch(1); Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
   assertTrue("Le fil UI ne doit pas attendre le chargement lent d’une discussion",heartbeat.await(1200,TimeUnit.MILLISECONDS))
  } finally {
   py.builtins.callAttr("exec", "import mobile_agent\nmobile_agent.get_session = mobile_agent._original_get_session", globals)
   m.callAttr("delete_session",id); a.finish()
  }
 }
 @Test fun longMessagesRemainCompleteAndOlderHistoryIsAccessible() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync(); val m=Python.getInstance().getModule("mobile_agent")
  val id=JSONObject(m.callAttr("create_session","chat").toString()).getString("id")
  val show=MainActivity::class.java.getDeclaredMethod("showChat",String::class.java).apply { isAccessible=true }
  val longText="a".repeat(7999)+"😀"+"b".repeat(9000)
  try {
   repeat(25) { m.callAttr("_message",id,"assistant","Message historique $it") }
   m.callAttr("_message",id,"assistant",longText)
   inst.runOnMainSync { show.invoke(a,id) }; awaitChat(a)
   var views=children(a.window.decorView)
   assertFalse("Les anciens messages ne sont pas tous construits au premier affichage",views.any { it is TextView && it.text.toString()=="Message historique 0" })
   assertTrue(views.any { it is TextView && it.text.toString().contains("partie 1/3") })
   val next=views.first { it.contentDescription=="Partie suivante du message" }
   inst.runOnMainSync { next.performClick() }; inst.waitForIdleSync()
   views=children(a.window.decorView)
   assertTrue("La paire Unicode n’est pas coupée",views.any { it is TextView && it.text.toString().startsWith("😀") })
   val copies=views.filter { it.contentDescription=="Copier la réponse" }
   inst.runOnMainSync { copies.last().performClick() }
   var copied=""; inst.runOnMainSync { val c=ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; copied=c.primaryClip!!.getItemAt(0).text.toString() }
   assertEquals("La copie conserve tout le message",longText,copied)
   inst.runOnMainSync { views.first { it.contentDescription=="Afficher les messages précédents" }.performClick() }; awaitChat(a)
   assertTrue("L’historique antérieur reste accessible",children(a.window.decorView).any { it is TextView && it.text.toString()=="Message historique 0" })
   val saved=JSONObject(m.callAttr("get_session",id).toString()).getJSONArray("messages").getJSONObject(25).getString("text")
   assertEquals(longText,saved)
  } finally { m.callAttr("delete_session",id); a.finish() }
 }
 @Test fun longLiveStreamShowsBoundedPreviewWithoutLosingData() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync(); val m=Python.getInstance().getModule("mobile_agent")
  val id=JSONObject(m.callAttr("create_session","chat").toString()).getString("id")
  val busy=JobRunner::class.java.getDeclaredField("busySession").apply { isAccessible=true }
  val show=MainActivity::class.java.getDeclaredMethod("showChat",String::class.java).apply { isAccessible=true }
  val original="Commencement "+"contenu 😀 ".repeat(13_000)+" FIN"
  try {
   busy.set(JobRunner,id); inst.runOnMainSync { show.invoke(a,id) }; awaitChat(a)
   val writer=Thread { for(i in 1..6) { var end=original.length*i/6; if(end<original.length && Character.isHighSurrogate(original[end-1]) && Character.isLowSurrogate(original[end])) end--; m.callAttr("_set_live",id,original.substring(0,end)) }; m.callAttr("_set_live",id,original) }
   writer.start(); writer.join(20_000); assertFalse("Le producteur local a terminé",writer.isAlive)
   val deadline=System.currentTimeMillis()+10_000; var preview=""
   while(System.currentTimeMillis()<deadline && !preview.endsWith(" FIN")) {
    inst.runOnMainSync { preview=children(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.firstOrNull { it.startsWith("Aperçu de la fin du flux") } ?: "" }
    if(!preview.endsWith(" FIN")) Thread.sleep(100)
   }
   assertTrue("L’aperçu doit inclure la fin du flux",preview.endsWith(" FIN"))
   assertTrue("L’aperçu reste borné",preview.length<12_200)
   assertEquals("La progression complète est conservée",original,JSONObject(m.callAttr("live_progress",id).toString()).getString("text"))
   val heartbeat=CountDownLatch(1); Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
   assertTrue("L’interface reste réactive",heartbeat.await(1500,TimeUnit.MILLISECONDS))
  } finally { busy.set(JobRunner,null); m.callAttr("delete_session",id); a.finish() }
 }
 @Test fun staleSlowLoadNeverReplacesNewerDiscussion() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync(); val py=Python.getInstance(); val m=py.getModule("mobile_agent"); val globals=py.builtins.callAttr("dict")
  val first=JSONObject(m.callAttr("create_session","chat").toString()).getString("id")
  val second=JSONObject(m.callAttr("create_session","chat").toString()).getString("id")
  m.callAttr("_message",first,"assistant","Ancienne discussion")
  m.callAttr("_message",second,"assistant","Discussion actuelle")
  val show=MainActivity::class.java.getDeclaredMethod("showChat",String::class.java).apply { isAccessible=true }
  try {
   val code="import mobile_agent, time\nmobile_agent._original_get_session = mobile_agent.get_session\ndef slow_first(sid, *args):\n if sid == "+py.builtins.callAttr("repr",first).toString()+": time.sleep(3)\n return mobile_agent._original_get_session(sid, *args)\nmobile_agent.get_session = slow_first"
   py.builtins.callAttr("exec",code,globals)
   inst.runOnMainSync { show.invoke(a,first) }; Thread.sleep(250)
   inst.runOnMainSync { show.invoke(a,second) }; awaitChat(a); Thread.sleep(3500); inst.waitForIdleSync()
   val views=children(a.window.decorView)
   assertTrue(views.any { it is TextView && it.text.toString()=="Discussion actuelle" })
   assertFalse("Une ancienne lecture ne doit pas remplacer la discussion courante",views.any { it is TextView && it.text.toString()=="Ancienne discussion" })
  } finally {
   py.builtins.callAttr("exec","import mobile_agent\nmobile_agent.get_session = mobile_agent._original_get_session",globals)
   m.callAttr("delete_session",first); m.callAttr("delete_session",second); a.finish()
  }
 }
 @Test fun sqliteLockMustNotFreezeTheUiWhileStreaming() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync(); val m=Python.getInstance().getModule("mobile_agent")
  val id=JSONObject(m.callAttr("create_session","chat").toString()).getString("id")
  val busy=JobRunner::class.java.getDeclaredField("busySession").apply { isAccessible=true }
  val show=MainActivity::class.java.getDeclaredMethod("showChat",String::class.java).apply { isAccessible=true }
  val locked=CountDownLatch(1); val release=CountDownLatch(1)
  var lockThread:Thread?=null
  try {
   m.callAttr("_message",id,"assistant","Réponse locale")
   busy.set(JobRunner,id); inst.runOnMainSync { show.invoke(a,id) }; awaitChat(a)
   lockThread=Thread {
    val db=SQLiteDatabase.openDatabase(ctx.filesDir.resolve("nemesis.sqlite3").path,null,SQLiteDatabase.OPEN_READWRITE)
    try { db.beginTransaction(); db.execSQL("UPDATE sessions SET updated=updated WHERE id=?",arrayOf(id)); locked.countDown(); release.await(10,TimeUnit.SECONDS); db.endTransaction() }
    finally { db.close() }
   }.apply { start() }
   assertTrue("Le verrou local doit être acquis",locked.await(5,TimeUnit.SECONDS))
   Thread.sleep(700) // The periodic UI observation attempts to read under the write lock.
   val heartbeat=CountDownLatch(1); Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
   assertTrue("Le fil UI ne doit pas attendre SQLite pendant le streaming",heartbeat.await(1300,TimeUnit.MILLISECONDS))
  } finally { release.countDown(); lockThread?.join(15000); busy.set(JobRunner,null); m.callAttr("delete_session",id); a.finish() }
 }
}
