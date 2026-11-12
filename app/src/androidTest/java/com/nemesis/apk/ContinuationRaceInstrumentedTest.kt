package com.nemesis.apk

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nemap.android.core.DeepSeek
import com.nemap.android.web.Engine
import com.nemap.android.web.WebViewHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/** Local deterministic reproduction, not a DeepSeek call. */
@RunWith(AndroidJUnit4::class)
class ContinuationRaceInstrumentedTest {
 @Test fun staleVisibleButtonMustNotEndANewEmptyStream() = runBlocking { exerciseFixture(1, true, false) }
 @Test fun twoConsecutiveButtonsStartExactlyTwoStreams() = runBlocking { exerciseFixture(2, false, false) }
 @Test fun unresolvedOldButtonIsNeverClickedTwice() = runBlocking { exerciseFixture(1, false, true) }
 private suspend fun exerciseFixture(rounds:Int, slowRead:Boolean, stuck:Boolean) {
  val requests=AtomicInteger(0); val server=ServerSocket(0,20,java.net.InetAddress.getByName("127.0.0.1"))
  val body="""<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>
   <div class="ds-message"><div class="ds-assistant-message-main-content">Ancienne réponse.</div></div>
   <button id="continue" style="width:160px;height:60px">Continue</button>
   <script>
   var turn=0;
   function bind(){ document.getElementById('continue').onclick=function(){
    var x=new XMLHttpRequest(); x.open('POST','/api/v0/chat/continue');
    x.onload=function(){
     turn++; document.querySelector('.ds-assistant-message-main-content').innerText+=' suite finale';
     var old=document.getElementById('continue');
     if(turn >= $rounds) { if(!${stuck}) old.style.display='none'; }
     else { var next=old.cloneNode(true); old.parentNode.replaceChild(next,old); bind(); }
    };
    x.send('{}');
   }; } bind();
   </script></body></html>"""
  val worker=Thread {
   while(!server.isClosed) {
    val s=try { server.accept() } catch(_:SocketException){ break }
    Thread {
     try {
      s.soTimeout=10000; val input=s.getInputStream(); val out=s.getOutputStream(); val lines=StringBuilder()
      var end=0; while(end!=4) { val b=input.read(); if(b<0)break; lines.append(b.toChar()); end=when { end==0 && b==13 -> 1; end==1 && b==10 -> 2; end==2 && b==13 -> 3; end==3 && b==10 -> 4; b==13 -> 1; else -> 0 } }
      val header=lines.toString(); val length=Regex("(?im)^content-length: *([0-9]+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
      repeat(length) { input.read() }
      if(header.startsWith("POST /api/v0/chat/continue")) {
       requests.incrementAndGet()
       val chunks=listOf("data: {\"v\":\" suite\"}\n\n","data: {\"v\":\" finale\"}\n\n","data: [DONE]\n\n")
       val lengthBytes=chunks.sumOf { it.toByteArray().size }
       out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: $lengthBytes\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
       Thread.sleep(1800)
       for((i,c) in chunks.withIndex()) { out.write(c.toByteArray()); out.flush(); if(i==0)Thread.sleep(400) }
      } else {
       val bytes=body.toByteArray(); out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(bytes); out.flush()
      }
     } catch(_:Exception) {} finally { try { s.close() } catch(_:Exception){} }
    }.apply { isDaemon=true; start() }
   }
  }.apply { isDaemon=true; start() }
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val main=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  inst.waitForIdleSync(); inst.runOnMainSync { WebViewHost.load("about:blank") }
  val browser=inst.startActivitySync(Intent(ctx,com.nemap.android.BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  try {
   inst.runOnMainSync { WebViewHost.load("http://127.0.0.1:${server.localPort}/fixture") }
   withTimeout(20_000) { while(!Engine.adapter("continueAvailable").optBoolean("available")) delay(100) }
   if (slowRead) {
   // The first read stalls the JS renderer beyond its deadline. Only the
   // read-only observation may be attempted again; no submission is replayed.
   Engine.evalJs("""(function(){var f=window.__napDS.continueAvailable;window.__slowReads=0;window.__napDS.continueAvailable=function(){window.__slowReads++;if(window.__slowReads===1){var end=Date.now()+700;while(Date.now()<end){}}return f()};return 'ok'})()""")
   var waits=0
   val recovered=Engine.readAdapter("continueAvailable",timeoutMs=450,onWait={waits++})
   assertTrue("Lecture DOM récupérée après un délai",recovered.optBoolean("available")); assertTrue("Une attente temporaire a été observée",waits>=1)
   assertEquals("2",Engine.evalJs("window.__slowReads"))
   try { Engine.readAdapter("submit"); fail("Une action ne doit jamais être rejouée par la récupération en lecture") } catch(_:IllegalArgumentException) {}
   }
   val before=Engine.adapter("answer").optString("text"); assertEquals("Ancienne réponse.",before)
   val streamed=StringBuilder()
   val result = try { withTimeout(40_000) { DeepSeek.continueAnswer(before,{ streamed.append(it) }) } }
    catch(e:com.nemap.android.core.NemApiException) {
     if (!stuck) throw e
     assertTrue("Raison explicite",e.message.contains("bouton de continuation précédent")); null
    }
   assertEquals("Un seul clic par continuation",rounds,requests.get()); assertEquals(" suite finale".repeat(rounds),streamed.toString())
   if(stuck) assertNull("Un ancien bouton indéterminé doit produire une erreur explicite",result)
   else assertEquals(before+streamed.toString(),result)
  } finally {
   inst.runOnMainSync { WebViewHost.load("about:blank") }; server.close(); browser.finish(); main.finish()
  }
 }
}
