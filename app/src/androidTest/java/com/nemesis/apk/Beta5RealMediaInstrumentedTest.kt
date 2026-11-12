package com.nemesis.apk

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in; one real task, with at most two tiny local test files. */
@RunWith(AndroidJUnit4::class)
class Beta5RealMediaInstrumentedTest {
 @Test fun readLocalImageAndPdfAsWorkTools() {
  val inst=InstrumentationRegistry.getInstrumentation()
  assumeTrue(InstrumentationRegistry.getArguments().getString("media_real")=="true")
  val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  inst.waitForIdleSync(); val m=Python.getInstance().getModule("mobile_agent")
  val id=JSONObject(m.callAttr("create_session","work").toString()).getString("id")
  try {
   val root=File(m.callAttr("_ensure_workspace",id).toString()); assertTrue(root.isDirectory)
   val image=Bitmap.createBitmap(800,300,Bitmap.Config.ARGB_8888)
   val c=Canvas(image); c.drawColor(Color.WHITE)
   val paint=Paint().apply { color=Color.BLACK; textSize=100f; isAntiAlias=true }
   c.drawText("IMAGE 746",50f,170f,paint)
   File(root,"image.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
   val doc=PdfDocument(); val page=doc.startPage(PdfDocument.PageInfo.Builder(600,400,1).create())
   page.canvas.drawText("DOCUMENT PDF 259",40f,100f,Paint().apply { textSize=32f; color=Color.BLACK })
   doc.finishPage(page); File(root,"document.pdf").outputStream().use { doc.writeTo(it) }; doc.close()
   val prompt="Utilise read_file sur les deux fichiers locaux image.png et document.pdf, puis donne uniquement le nombre écrit dans chacun."
   assertTrue(JobRunner.start(ctx,id,prompt,"[]",prompt))
   val end=System.currentTimeMillis()+240_000
   while(JobRunner.busySession!=null && System.currentTimeMillis()<end) Thread.sleep(500)
   if(JobRunner.busySession!=null) { m.callAttr("cancel"); fail("Délai dépassé") }
   val state=JSONObject(m.callAttr("get_session",id).toString()); val arr=state.getJSONArray("messages")
   val tools=mutableListOf<JSONObject>(); var answer=""; var error=""
   for (i in 0 until arr.length()) {
    val msg=arr.getJSONObject(i)
    if(msg.optString("role")=="tool") { val x=JSONObject(msg.optString("text")); if(x.optString("name")=="read_file") tools.add(x) }
    if(msg.optString("role")=="assistant") answer=msg.optString("text")
    if(msg.optString("role")=="error") error=msg.optString("text")
   }
   assertTrue("Échec de tâche : $error",error.isEmpty())
   assertTrue("Les deux fichiers doivent passer par read_file",tools.count { it.optJSONObject("result")?.optBoolean("success")==true }>=2)
   assertTrue("Réponse image non confirmée : $answer",answer.contains("746"))
   assertTrue("Réponse PDF non confirmée : $answer",answer.contains("259"))
  } finally {
   if(JobRunner.busySession==null) {
    val root=File(m.callAttr("workspace_root",id).toString()); File(root,"image.png").delete(); File(root,"document.pdf").delete(); root.delete(); m.callAttr("delete_session",id)
   }
   a.finish()
  }
 }
}
