package com.nemap.android.core

import org.json.JSONObject

/** The button used to start a continuation is not the next continuation.
 * A new opportunity must first disappear/disable or have a new DOM identity. */
internal class ContinueGate(private val previousId: String) {
    private var departed = false
    fun observe(status: JSONObject): Boolean {
        // Failed or missing adapter observations must not masquerade as a
        // disappeared button, which could authorize a duplicate tap.
        if (!status.has("available")) return false
        val available = status.optBoolean("available")
        val enabled = available && status.optBoolean("enabled", true)
        val id = status.optString("id", "")
        if (!enabled || (previousId.isNotEmpty() && id.isNotEmpty() && id != previousId)) departed = true
        return departed && enabled
    }
    fun waitingOnOldButton(status: JSONObject): Boolean = !departed && status.optBoolean("available") && status.optBoolean("enabled", true)
}
