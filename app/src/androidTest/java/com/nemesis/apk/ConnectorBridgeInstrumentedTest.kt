package com.nemesis.apk

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Keystore/preferences round trips with synthetic credentials; user state is restored. */
@RunWith(AndroidJUnit4::class)
class ConnectorBridgeInstrumentedTest {
    private fun snapshot(prefs: SharedPreferences): Map<String, Any?> = prefs.all.toMap()
    private fun restore(prefs: SharedPreferences, values: Map<String, Any?>) {
        val edit = prefs.edit().clear()
        for ((key, value) in values) when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
        }
        check(edit.commit())
    }

    @Test fun githubTokenIsTrimmedEncryptedAndNeverReturnedByStatus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GitHubConnectorBridge.init(context)
        val prefs = context.getSharedPreferences("connector_github", Context.MODE_PRIVATE)
        val backup = snapshot(prefs)
        val fake = "github_pat_" + "synthetic_test_".repeat(3)
        try {
            GitHubConnectorBridge.save(" alice ", " \n$fake\t ")
            assertTrue(GitHubConnectorBridge.configured())
            assertEquals("alice", GitHubConnectorBridge.username())
            assertEquals(fake, GitHubConnectorBridge.token())
            val status = JSONObject(GitHubConnectorBridge.status())
            assertEquals("alice", status.optString("account"))
            assertFalse(status.toString().contains(fake))
            assertFalse(prefs.getString("token", "").orEmpty().contains(fake))
        } finally { restore(prefs, backup) }
    }

    @Test fun googleAccountMetadataRoundTripsAndRestoresExistingState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GoogleWorkspaceBridge.init(context)
        val prefs = context.getSharedPreferences("connector_google_workspace", Context.MODE_PRIVATE)
        val backup = snapshot(prefs)
        try {
            GoogleWorkspaceBridge.saveAccount("synthetic@example.test")
            assertTrue(GoogleWorkspaceBridge.configured())
            assertEquals("synthetic@example.test", GoogleWorkspaceBridge.email())
            val status = JSONObject(GoogleWorkspaceBridge.status())
            assertEquals("synthetic@example.test", status.optString("account"))
        } finally { restore(prefs, backup) }
    }
}
