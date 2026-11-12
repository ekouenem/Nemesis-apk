package com.nemesis.apk

import android.content.Intent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in local WebView-only test: no DeepSeek request and no account use. */
@RunWith(AndroidJUnit4::class)
class Beta5LargeTransferInstrumentedTest {
 @Test fun fiftyMebibytesIntoLocalFileInput() {
  val inst=InstrumentationRegistry.getInstrumentation()
  assumeTrue(InstrumentationRegistry.getArguments().getString("large_transfer")=="true")
  val a=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  lateinit var web:WebView
  val loaded=CountDownLatch(1)
  inst.runOnMainSync {
   web=WebView(a); web.settings.javaScriptEnabled=true
   web.webViewClient=object:android.webkit.WebViewClient() {
    override fun onPageFinished(view:WebView?,url:String?) { if(url?.startsWith("https://example.invalid/")==true) loaded.countDown() }
   }
   web.loadDataWithBaseURL("https://example.invalid/","<html><body><input id='f' type='file' multiple></body></html>","text/html","UTF-8",null)
  }
  fun js(code:String):String {
   val latch=CountDownLatch(1); var output:String?=null
   inst.runOnMainSync { web.evaluateJavascript(code) { output=it; latch.countDown() } }
   assertTrue("WebView locale sans réponse",latch.await(30,TimeUnit.SECONDS)); return output ?: ""
  }
  try {
   assertTrue("La page locale doit finir de charger",loaded.await(30,TimeUnit.SECONDS))
   assertEquals("true",js("!!document.getElementById('f')"))
   val script=inst.targetContext.assets.open("js/adapter-deepseek.js").bufferedReader().use { it.readText() }
   js(script); assertTrue(js("typeof window.__napDS").contains("object"))
   assertTrue(js("window.__napDS.attachBegin()").contains("ok"))
   val block=ByteArray(72_000) { 0x35 }; var offset=0; val size=50*1024*1024
   while(offset<size) {
    val n=minOf(block.size,size-offset)
    val payload=JSONObject().put("first",offset==0).put("mime","application/pdf").put("name","test.pdf")
     .put("b64",android.util.Base64.encodeToString(block,0,n,android.util.Base64.NO_WRAP)).toString()
    val result=js("window.__napDS.attachChunk(${JSONObject.quote(payload)})")
    assertTrue("Bloc interrompu à $offset",result.contains("\\\"ok\\\":true"))
    offset+=n
   }
   val committed=js("window.__napDS.attachCommit()"); assertTrue("Injection impossible : $committed",committed.contains("\\\"ok\\\":true"))
   assertEquals("52428800",js("document.getElementById('f').files[0].size"))
   assertEquals("\"application/pdf\"",js("document.getElementById('f').files[0].type"))
  } finally { inst.runOnMainSync { web.destroy(); a.finish() } }
 }
}
