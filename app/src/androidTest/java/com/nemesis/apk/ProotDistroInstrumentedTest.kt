package com.nemesis.apk

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Debian 13 is the supported 32-bit Debian environment; Ubuntu has no matching rootfs here. */
@RunWith(AndroidJUnit4::class)
class ProotDistroInstrumentedTest {
    @Test fun debianInstallAndRunThroughTheAgent() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        if(!Python.isStarted()) Python.start(AndroidPlatform(context))
        val dns=(context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
            .activeNetwork?.let { network -> (context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress?.substringBefore('%') } }
            .orEmpty().joinToString(",")
        val agent=Python.getInstance().getModule("mobile_agent")
        agent.callAttr("configure",context.filesDir.absolutePath,8090,"","",context.applicationInfo.nativeLibraryDir,Build.SUPPORTED_ABIS.first(),dns)
        val state=JSONObject(agent.callAttr("environment_status").toString())
        val items=state.getJSONArray("items")
        val ids=(0 until items.length()).map { items.getJSONObject(it).optString("id") }.toSet()
        assertFalse("Ubuntu must be removed from the supported 32-bit runtime list", "ubuntu" in ids)
        assertTrue("Debian must be supported on ${Build.SUPPORTED_ABIS.first()}", "debian" in ids)
        val install=JSONObject(agent.callAttr("environment_install","debian").toString())
        assertTrue("Debian install failed: ${install.optString("error")}",install.optBoolean("success"))
        assertTrue("Debian not marked installed",install.optBoolean("installed"))
        assertTrue("Debian installed-size metric missing",install.optLong("actual_installed_bytes")>50_000_000L)
        val selected=JSONObject(agent.callAttr("environment_set","debian").toString())
        assertTrue("Debian selection failed: ${selected.optString("error")}",selected.optBoolean("success"))
        val result=JSONObject(agent.callAttr("_execute_bash",
            "set -eu; grep -F 'VERSION_CODENAME=trixie' /etc/os-release; grep -F 'Debian GNU/Linux 13' /etc/os-release; printf 'NEMESIS_DEBIAN_PROOT_OK\n'",
            90,null).toString())
        assertTrue("Debian routed agent command failed: ${result.optString("error")} ${result.optString("output")}",result.optBoolean("success"))
        assertTrue("Debian release mismatch: ${result.optString("output")}",result.optString("output").contains("VERSION_CODENAME=trixie"))
        val dpkg=JSONObject(agent.callAttr("_execute_bash","dpkg --version",90,null).toString())
        assertTrue("Debian dpkg unavailable: ${dpkg.optString("error")} ${dpkg.optString("output")}",dpkg.optBoolean("success"))
        assertTrue(dpkg.optString("output").contains("dpkg"))
    }
}
