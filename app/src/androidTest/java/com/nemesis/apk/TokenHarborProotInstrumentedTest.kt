package com.nemesis.apk

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
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

/** Live provider tests verify the complete model -> tool -> PRoot path on both distributions. */
@RunWith(AndroidJUnit4::class)
class TokenHarborProotInstrumentedTest {
    @Test fun liveModelUsesPreinstalledToolchainInAlpine() = verifyToolchain("alpine")
    @Test fun liveModelUsesPreinstalledToolchainInDebian() = verifyToolchain("debian")

    private fun verifyToolchain(distro:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val apiKey=InstrumentationRegistry.getArguments().getString("tokenharbor_api_key").orEmpty()
        assumeTrue("Temporary TokenHarbor key not supplied; skipping opt-in API test",apiKey.length>=16)
        if(!Python.isStarted()) Python.start(AndroidPlatform(context))
        val module=Python.getInstance().getModule("mobile_agent")
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val dns=manager.activeNetwork?.let { manager.getLinkProperties(it)?.dnsServers.orEmpty() }
            .orEmpty().mapNotNull { it.hostAddress?.substringBefore('%') }.joinToString(",")
        val testRoot=File(context.cacheDir,"tokenharbor-proot-$distro")
        testRoot.deleteRecursively(); check(testRoot.mkdirs())
        module.callAttr("configure",testRoot.absolutePath,8090,"","",context.applicationInfo.nativeLibraryDir,Build.SUPPORTED_ABIS.first(),dns)
        module.callAttr("configure_tokenharbor",apiKey)
        try {
            val install=JSONObject(module.callAttr("environment_install",distro).toString())
            assertTrue("$distro rootfs/toolchain install failed: ${install.optString("error")}",install.optBoolean("success"))
            assertTrue("$distro toolchain marker missing",install.optBoolean("toolchain_ready"))
            val selected=JSONObject(module.callAttr("environment_set",distro).toString())
            assertTrue("$distro activation failed: ${selected.optString("error")}",selected.optBoolean("success"))
            val catalog=JSONObject(module.callAttr("tokenharbor_probe_models").toString())
            assertTrue("TokenHarbor /v1/models failed",catalog.optBoolean("ok"))
            assertTrue("Selected model unavailable",catalog.optBoolean("selected_model_available"))
            val created=JSONObject(module.callAttr("create_session","code").toString())
            assertTrue("Code test session creation failed",created.optBoolean("ok"))
            val sid=created.getString("id")
            val marker="TOKENHARBOR_${distro.uppercase()}_TOOLCHAIN_OK"
            val distroCheck=if(distro=="alpine") "cat /etc/alpine-release" else "grep -F ID=debian /etc/os-release"
            val prompt="Use the bash tool exactly once in the active local $distro/PRoot environment. Run: set -eu; printf '$marker '; git --version; python3 --version; node --version; npm --version; $distroCheck. Do not use SSH, GitHub, or any other execution route."
            val sent=JSONObject(module.callAttr("send",sid,prompt,"[]").toString())
            assertTrue("Live TokenHarbor agent turn failed for $distro",sent.optBoolean("ok"))
            val session=JSONObject(module.callAttr("get_session",sid).toString())
            val messages=session.optJSONArray("messages") ?: org.json.JSONArray()
            var verified=false
            for(i in 0 until messages.length()) {
                val message=messages.optJSONObject(i) ?: continue
                if(message.optString("role")!="tool") continue
                val body=JSONObject(message.optString("text","{}"))
                if(body.optString("name")!="bash") continue
                val result=body.optJSONObject("result") ?: continue
                val output=result.optString("output")
                val all=output.lowercase()
                if(result.optBoolean("success") && result.optString("executor")=="proot" && result.optString("distro")==distro &&
                    output.contains(marker) && all.contains("git version") && all.contains("python") && all.contains("npm") && all.contains("node")) verified=true
            }
            assertTrue("Agent did not persist a successful $distro toolchain bash result",verified)
            val metrics=JSONObject(module.callAttr("tokenharbor_request_metrics").toString())
            assertEquals("One free catalog request expected",1,metrics.optInt("models"))
            assertTrue("No live TokenHarbor chat request observed",metrics.optInt("chat")>=1)
            println("TOKENHARBOR_LIVE_TEST pipeline=agent->bash->$distro-proot toolchain=git,python,node,npm chat_requests=${metrics.optInt("chat")} result=verified")
        } finally {
            module.callAttr("configure_tokenharbor","")
            testRoot.deleteRecursively()
        }
    }
}
