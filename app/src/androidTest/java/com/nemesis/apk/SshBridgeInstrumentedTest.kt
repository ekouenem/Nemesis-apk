package com.nemesis.apk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Explicit opt-in: a temporary test server is supplied by instrumentation arguments. */
@RunWith(AndroidJUnit4::class)
class SshBridgeInstrumentedTest {
    @Test fun pinnedHostAndCommandOnTemporaryServer() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("test_ssh_host") ?: ""
        val user = args.getString("test_ssh_user") ?: ""
        val password = args.getString("test_ssh_password") ?: ""
        val fingerprint = args.getString("test_ssh_fingerprint") ?: ""
        val port = args.getString("test_ssh_port")?.toIntOrNull() ?: 0
        assumeTrue("External temporary SSH fixture not configured", host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty() && fingerprint.isNotEmpty() && port > 0)
        SshBridge.init(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeFalse("User SSH configuration must not be overwritten", SshBridge.details().optBoolean("configured"))
        try {
            SshBridge.save(host, port, user, fingerprint, password)
            val result = JSONObject(SshBridge.execute("printf nemesis_ssh_test", 20))
            assertTrue(result.optString("error"), result.optBoolean("success"))
            assertEquals("nemesis_ssh_test", result.optString("output"))
            SshBridge.save(host, port, user, "SHA256:" + "A".repeat(43), password)
            val invalid = JSONObject(SshBridge.execute("printf must_not_run", 20))
            assertFalse("Untrusted host fingerprint must fail", invalid.optBoolean("success"))
            assertFalse(invalid.optString("output").contains("must_not_run"))
        } finally { SshBridge.clear() }
    }
}
