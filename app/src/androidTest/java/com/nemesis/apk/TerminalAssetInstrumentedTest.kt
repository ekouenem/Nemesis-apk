package com.nemesis.apk

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Guards the locally bundled xterm assets against the API 27 System WebView parser/runtime. */
@RunWith(AndroidJUnit4::class)
class TerminalAssetInstrumentedTest {
    @Test fun xtermLoadsAndSignalsReadyInSystemWebView() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val activityRef=AtomicReference<Activity?>(null)
        instrumentation.runOnMainSync {
            val current=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull { it is MainActivity }
            if(current is Activity) activityRef.set(current)
            else context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val activityDeadline=System.currentTimeMillis()+10_000
        while(activityRef.get()==null && System.currentTimeMillis()<activityDeadline) {
            instrumentation.runOnMainSync {
                val current=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull { it is MainActivity }
                if(current is Activity) activityRef.set(current)
            }
            Thread.sleep(100)
        }
        val activity=activityRef.get() ?: throw AssertionError("NEMESIS MainActivity did not reach RESUMED")
        val ready=CountDownLatch(1)
        val dimensions=AtomicReference("")
        val scriptError=AtomicReference("")
        var terminal:WebView?=null
        var dialog:Dialog?=null
        instrumentation.runOnMainSync {
            val d=Dialog(activity)
            dialog=d
            val root=FrameLayout(activity)
            val web=WebView(activity)
            terminal=web
            web.setBackgroundColor(android.graphics.Color.BLACK)
            web.settings.javaScriptEnabled=true
            web.settings.allowFileAccess=true
            web.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface fun ready(columns:Int,rows:Int) {
                    dimensions.set("${columns}x${rows}")
                    ready.countDown()
                }
                @android.webkit.JavascriptInterface fun sendInput(encoded:String) {}
                @android.webkit.JavascriptInterface fun resize(columns:Int,rows:Int) {}
            },"Nemesis")
            web.webChromeClient=object : WebChromeClient() {
                override fun onConsoleMessage(message:ConsoleMessage):Boolean {
                    if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR) scriptError.compareAndSet("",message.message())
                    return true
                }
            }
            root.addView(web,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
            d.setContentView(root)
            d.show()
            d.window?.setLayout(900,900)
            web.loadUrl("file:///android_asset/terminal/index.html")
        }
        try {
            assertTrue("Bundled xterm failed to initialize in the system WebView: ${scriptError.get()}",ready.await(15,TimeUnit.SECONDS))
            val size=dimensions.get().split("x")
            assertTrue("xterm reported invalid grid dimensions: ${dimensions.get()}",size.size==2 && size[0].toInt()>0 && size[1].toInt()>0)
            assertTrue("Unexpected JavaScript error during xterm load: ${scriptError.get()}",scriptError.get().isEmpty())
        } finally {
            instrumentation.runOnMainSync { dialog?.dismiss(); terminal?.destroy(); activity.finish() }
        }
    }
}
