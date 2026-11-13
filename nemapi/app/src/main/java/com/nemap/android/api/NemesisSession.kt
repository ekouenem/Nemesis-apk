package com.nemap.android.api

import com.nemap.android.core.NemApiException
import com.nemap.android.web.Engine
import com.nemap.android.web.WebViewHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

/** One WebView is shared by all sessions: callers serialize navigation + generation. */
object NemesisSession {
    private const val HOME = "https://chat.deepseek.com/"
    private val CHAT_PATH = Regex("^/a/chat/s/[A-Za-z0-9_-]{6,128}/?$")

    fun validConversationUrl(value: String): Boolean = try {
        val uri = URI(value)
        value.length <= 512 && uri.scheme == "https" && uri.host == "chat.deepseek.com" &&
            uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            CHAT_PATH.matches(uri.rawPath ?: "")
    } catch (_: Exception) { false }

    private fun normalized(url: String) = url.trimEnd('/')

    fun targetUrl(config: JSONObject): String {
        val fresh = config.optBoolean("fresh_chat", false)
        val url = config.optString("conversation_url", "").trim()
        if (fresh == url.isNotEmpty()) throw NemApiException(
            NemApiException.PROVIDER_NOT_CONFIGURED,
            "Indique soit fresh_chat=true, soit une URL de conversation DeepSeek valide."
        )
        if (!fresh && !validConversationUrl(url)) throw NemApiException(
            NemApiException.PROVIDER_NOT_CONFIGURED, "URL de conversation DeepSeek invalide."
        )
        return if (fresh) HOME else url
    }

    suspend fun prepare(config: JSONObject) {
        val fresh = config.optBoolean("fresh_chat", false)
        val target = targetUrl(config)
        if (!WebViewHost.isCreated) throw NemApiException.notReady("Ouvre d'abord DeepSeek dans NemApi.")
        val current = try { Engine.currentUrl() } catch (_: Throwable) { "" }
        if (fresh || normalized(current) != normalized(target)) {
            withContext(Dispatchers.Main) { WebViewHost.load(target) }
        }
        // Even if URL already matches, the page can be loading, rebuilding the
        // React composer, or recovering from a renderer crash. Never treat URL
        // equality alone as proof the input is ready.
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
        var checkedPageEvent = Long.MIN_VALUE
        var adapterPageEvent = Long.MIN_VALUE
        var lastUrlCheckAt = Long.MIN_VALUE
        var targetPage = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            delay(750)
            if (WebViewHost.pageLoading) continue
            val now = android.os.SystemClock.elapsedRealtime()
            val pageEvent = WebViewHost.lastPageEventAt
            val stableMs = now - pageEvent
            if (stableMs < 1000) continue
            try {
                // URL and readiness were previously re-evaluated every 350 ms
                // (including a separate document.readyState call). On a long
                // conversation this queued redundant work behind a busy renderer.
                // Check URL on page events and occasionally for SPA route changes.
                if (pageEvent != checkedPageEvent || now - lastUrlCheckAt >= 3_000) {
                    checkedPageEvent = pageEvent
                    lastUrlCheckAt = now
                    targetPage = normalized(Engine.currentUrl()) == normalized(target)
                }
                if (!targetPage) continue
                if (adapterPageEvent != pageEvent) {
                    Engine.ensureAdapter()
                    adapterPageEvent = pageEvent
                }
                if (Engine.adapter("ready", timeoutMs = 5_000).optBoolean("ok")) return
            } catch (_: Throwable) { /* transient DOM/renderer failure before submit */ }
        }
        throw NemApiException.timeout("Conversation DeepSeek non prête ; aucun message n'a été envoyé.")
    }

    suspend fun resultUrl(): String? {
        repeat(20) {
            val url = try { Engine.currentUrl() } catch (_: Throwable) { "" }
            if (validConversationUrl(url)) return url
            delay(250)
        }
        return null
    }
}
