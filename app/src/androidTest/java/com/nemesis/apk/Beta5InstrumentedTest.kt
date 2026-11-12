package com.nemesis.apk
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Beta5InstrumentedTest {
 private fun children(v:View):List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
 @Test fun taskDurationOnlyWithoutRatingsInWorkAndCode() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync(); val m=Python.getInstance().getModule("mobile_agent")
  val show=MainActivity::class.java.getDeclaredMethod("showChat",String::class.java).apply { isAccessible=true }
  try {
   for(mode in listOf("work","code")) {
    val id=JSONObject(m.callAttr("create_session",mode).toString()).getString("id")
    try {
     m.callAttr("_message",id,"assistant","Terminé",12345)
     inst.runOnMainSync { show.invoke(a,id) }; inst.waitForIdleSync()
     val views=children(a.window.decorView)
     assertTrue("Durée absente en $mode",views.any { it.contentDescription=="Durée de la tâche : 12 s" })
     assertFalse(views.any { it.contentDescription=="Bonne réponse" || it.contentDescription=="Mauvaise réponse" })
     val result=JSONObject(m.callAttr("_tool","write_file",Python.getInstance().getModule("json").callAttr("loads", "{\"path\":\"test.txt\",\"content\":\"ok\"}"),id).toString())
     assertTrue(result.optBoolean("success"))
    } finally {
     val dir=java.io.File(m.callAttr("workspace_root",id).toString())
     java.io.File(dir,"test.txt").delete(); dir.delete()
     m.callAttr("delete_session",id)
    }
   }
  } finally { a.finish() }
 }
 @Test fun nemapiBrowserButtonKeepsCurrentPage() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,com.nemap.android.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  inst.waitForIdleSync()
  val before=com.nemap.android.web.WebViewHost.currentUrl
  val button=children(a.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString()=="Ouvrir le navigateur DeepSeek" }
  val monitor=inst.addMonitor(com.nemap.android.BrowserActivity::class.java.name,null,false)
  try {
   inst.runOnMainSync { button.performClick() }
   val browser=inst.waitForMonitorWithTimeout(monitor,10000)
   val opened=browser ?: throw AssertionError("Le navigateur doit s’ouvrir")
   assertFalse("Il ne doit pas forcer un retour à l’accueil",opened.intent.hasExtra("url"))
   assertEquals(before,com.nemap.android.web.WebViewHost.currentUrl)
   opened.finish()
  } finally { inst.removeMonitor(monitor); a.finish() }
 }
 @Test fun uploadedImageAppearsInsideItsMessage() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync(); val m=Python.getInstance().getModule("mobile_agent")
  val id=JSONObject(m.callAttr("create_session","chat").toString()).getString("id")
  val file=java.io.File(ctx.filesDir,"attachments/ui-preview-test"); file.parentFile?.mkdirs()
  val bmp=android.graphics.Bitmap.createBitmap(48,48,android.graphics.Bitmap.Config.ARGB_8888); bmp.eraseColor(android.graphics.Color.BLUE)
  file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle()
  try {
   val data=org.json.JSONArray().put(JSONObject().put("name","photo.png").put("mime","image/png").put("path",file.absolutePath).put("size",file.length()))
   val pythonList=Python.getInstance().getModule("json").callAttr("loads",data.toString())
   m.callAttr("_message",id,"user","Regarde",null,pythonList)
   val show=MainActivity::class.java.getDeclaredMethod("showChat",String::class.java).apply { isAccessible=true }
   inst.runOnMainSync { show.invoke(a,id) }; inst.waitForIdleSync()
   val views=children(a.window.decorView)
   val card=views.first { it.contentDescription=="Pièce jointe : photo.png" }
   assertTrue("L’aperçu doit être dans la bulle contenant le message",views.any { it is android.widget.TextView && it.text.toString()=="Regarde" && it.parent === card.parent })
   val thumb=children(card).filterIsInstance<android.widget.ImageView>().first { it.contentDescription=="Aperçu de photo.png" }
   val end=System.currentTimeMillis()+5000; while(thumb.drawable==null && System.currentTimeMillis()<end) { Thread.sleep(100); inst.waitForIdleSync() }
   assertNotNull("La vignette d’image doit être chargée",thumb.drawable)
  } finally { m.callAttr("delete_session",id); file.delete(); a.finish() }
 }
 @Test fun privatePdfPathAllows50MiBButRejectsExternalAndOversize() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val dir=java.io.File(ctx.filesDir,"attachments"); dir.mkdirs(); val file=java.io.File(dir,"pdf-50mb-test")
  val outside=java.io.File(ctx.filesDir,"outside-test.pdf"); outside.writeBytes("%PDF-".toByteArray())
  val method=com.nemap.android.api.ApiServer::class.java.getDeclaredMethod("decodePrivateAttachment",String::class.java,String::class.java,String::class.java).apply { isAccessible=true }
  val internal=com.nemap.android.api.ApiServer(ctx,0,"local-test-key")
  val external=com.nemap.android.api.ApiServer(ctx,0,null)
  fun reject(server:com.nemap.android.api.ApiServer,path:String) { try { method.invoke(server,path,"doc.pdf","application/pdf"); fail("Le chemin ou la taille doivent être refusés") } catch(e:java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is IllegalArgumentException) } }
  try {
   java.io.RandomAccessFile(file,"rw").use { it.write("%PDF-".toByteArray()); it.setLength(50L*1024*1024) }
   val parts=method.invoke(internal,file.absolutePath,"doc.pdf","application/pdf") as List<*>
   val part=parts[0] as com.nemap.android.core.ImagePart; assertEquals(50L*1024*1024,part.length()); assertEquals(0,part.bytes.size)
   reject(internal,outside.absolutePath); reject(external,file.absolutePath)
   java.io.RandomAccessFile(file,"rw").use { it.setLength(50L*1024*1024+1) }; reject(internal,file.absolutePath)
  } finally { file.delete(); outside.delete() }
 }
 @Test fun webSearchWorksInAndroidWithoutSsh() {
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  inst.waitForIdleSync()
  try {
   val m=Python.getInstance().getModule("mobile_agent")
   val r=JSONObject(m.callAttr("_tool","web_search", Python.getInstance().getModule("json").callAttr("loads", "{\"query\":\"capitale du Togo\"}")).toString())
   assertTrue(r.optString("error"),r.optBoolean("success")); assertTrue(r.getJSONArray("results").length()>0)
  } finally { a.finish() }
 }
}
