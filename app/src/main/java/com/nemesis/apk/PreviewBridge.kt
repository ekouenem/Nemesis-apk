package com.nemesis.apk

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.lang.ref.WeakReference

/** Opens the active local development server inside NEMESIS. */
object PreviewBridge {
    @Volatile private var activity = WeakReference<MainActivity>(null)
    @JvmStatic fun init(value: MainActivity) { activity = WeakReference(value) }
    @JvmStatic fun clear(value: MainActivity) { if (activity.get() === value) activity.clear() }
    @JvmStatic fun open(url: String): Boolean {
        val target = activity.get() ?: return false
        if (!url.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}"))) return false
        target.runOnUiThread { if (!target.isFinishing && !target.isDestroyed) target.showWebPreview(url) }
        return true
    }
}
