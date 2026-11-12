package com.nemesis.apk

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Wait for the actual asynchronous chat load, not just the main looper. */
internal fun awaitChat(a:MainActivity) {
 val inst=InstrumentationRegistry.getInstrumentation()
 val field=MainActivity::class.java.getDeclaredField("chatLoadingId").apply { isAccessible=true }
 val deadline=System.currentTimeMillis()+20_000
 var done=false
 while(!done && System.currentTimeMillis()<deadline) {
  inst.runOnMainSync { done=field.get(a)==null }
  if(!done) Thread.sleep(50)
 }
 assertTrue("Chargement de la discussion non terminé",done)
 inst.waitForIdleSync()
}
