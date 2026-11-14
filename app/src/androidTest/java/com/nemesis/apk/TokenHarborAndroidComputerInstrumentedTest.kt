package com.nemesis.apk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in live TokenHarbor pipeline: model tool call -> Python dispatcher -> Android PackageManager. */
@RunWith(AndroidJUnit4::class)
class TokenHarborAndroidComputerInstrumentedTest {
    @Test fun liveModelCallsAndroidAppInventoryTool() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val apiKey=InstrumentationRegistry.getArguments().getString("tokenharbor_api_key").orEmpty()
        assumeTrue("Temporary TokenHarbor key not supplied; skipping opt-in API test",apiKey.length>=16)
        if(!Python.isStarted()) Python.start(AndroidPlatform(context))
        AndroidComputerBridge.init(context)
        val root=File(context.cacheDir,"tokenharbor-android-computer-test")
        root.deleteRecursively(); check(root.mkdirs())
        val module=Python.getInstance().getModule("mobile_agent")
        module.callAttr("configure",root.absolutePath,8090,"","",context.applicationInfo.nativeLibraryDir,android.os.Build.SUPPORTED_ABIS.first(),"")
        module.callAttr("configure_tokenharbor",apiKey)
        try {
            val catalog=JSONObject(module.callAttr("tokenharbor_probe_models").toString())
            println("TOKENHARBOR_CATALOG ok=${catalog.optBoolean("ok")} status=${catalog.optInt("http_status")} model_available=${catalog.optBoolean("selected_model_available")} count=${catalog.optInt("model_count")}")
            val created=JSONObject(module.callAttr("create_session","work").toString())
            assertTrue("Work test session was not created",created.optBoolean("ok"))
            val sid=created.getString("id")
            val prompt="Use android_device(action='list_apps') exactly once to inspect the installed launcher apps. Do not use bash, MCP, or another tool. Then report how many apps the tool returned."
            val sent=JSONObject(module.callAttr("send",sid,prompt,"[]").toString())
            println("TOKENHARBOR_AGENT_DIAGNOSTIC ok=${sent.optBoolean("ok")} error=${sent.optString("error").take(160)}")
            assertTrue("Live TokenHarbor agent turn failed",sent.optBoolean("ok"))
            val session=JSONObject(module.callAttr("get_session",sid).toString())
            val messages=session.optJSONArray("messages") ?: org.json.JSONArray()
            var verified=false
            for(i in 0 until messages.length()) {
                val message=messages.optJSONObject(i) ?: continue
                if(message.optString("role")!="tool") continue
                val body=JSONObject(message.optString("text","{}"))
                if(body.optString("name")!="android_device") continue
                val result=body.optJSONObject("result") ?: continue
                val apps=result.optJSONArray("apps")
                if(result.optBoolean("success") && apps!=null && apps.length()>0) verified=true
            }
            assertTrue("The model did not execute android_device/list_apps through the native package manager",verified)
            val metrics=JSONObject(module.callAttr("tokenharbor_request_metrics").toString())
            assertTrue("No real TokenHarbor chat request observed",metrics.optInt("chat")>=1)
            println("TOKENHARBOR_LIVE_TEST pipeline=agent->android_device->PackageManager app_count=verified chat_requests=${metrics.optInt("chat")}")
        } finally {
            module.callAttr("configure_tokenharbor","")
            root.deleteRecursively()
        }
    }
}
