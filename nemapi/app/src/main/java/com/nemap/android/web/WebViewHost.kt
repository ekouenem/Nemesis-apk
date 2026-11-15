package com.nemap.android.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nemap.android.core.AppState
import com.nemap.android.core.DomHealth
import com.nemap.android.core.Cookies
import com.nemap.android.core.NemLog

/**
 * Hôte unique du WebView : créé une fois, jamais détruit, détaché de
 * l'Activity quand elle s'arrête. C'est ce qui permet à la page de continuer
 * à travailler quand l'utilisateur quitte l'application (le ForegroundService
 * garde le processus vivant).
 */
object WebViewHost {

    /**
     * Identité du navigateur.
     *
     * `desktop` : UA de bureau (le DOM renvoyé par DeepSeek est alors celui du
     * site ordinateur, celui que l'adaptateur connaît). La VERSION de Chrome
     * est celle du WebView réellement installé : annoncer « Chrome/126 » alors
     * que le moteur est un autre Chromium crée une incohérence que le
     * provider peut détecter (en-têtes client, `navigator.userAgentData`).
     *
     * `android` : UA Android cohérent avec l'appareil (WebView, WebGL, écran,
     * tactile). Identité beaucoup plus homogène pour le moteur de risque, mais
     * DeepSeek sert alors son interface mobile : à valider avant de l'adopter.
     */
    const val IDENTITY_DESKTOP = "desktop"
    const val IDENTITY_ANDROID = "android"

    private const val PREFS = "nemapi_identity"

    /** Version de Chrome annoncée par défaut si le WebView installé est inconnu. */
    const val FALLBACK_CHROME_MAJOR = 126

    val DESKTOP_UA: String
        get() = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${chromeMajor()}.0.0.0 Safari/537.36"

    @Volatile
    var webView: WebView? = null
        private set

    @Volatile
    var currentUrl: String = "https://chat.deepseek.com/"
        private set

    @Volatile var pageLoading: Boolean = false
        private set
    @Volatile var lastPageEventAt: Long = 0L
        private set

    private var lastSafeUrl: String = "https://chat.deepseek.com/"
    private val headersLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun rememberSafeUrl(url:String?) {
        try { val u=java.net.URI(url ?: ""); if(u.scheme=="https" && u.host=="chat.deepseek.com" && u.port == -1 && u.userInfo==null) lastSafeUrl=url!! } catch(_:Exception) {}
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var container: ViewGroup? = null
    private var appContext: Context? = null

    val isCreated: Boolean get() = webView != null

    // ---- identité du navigateur ----------------------------------------

    /**
     * Version majeure de Chrome réellement installée (moteur du WebView).
     * `WebView.getCurrentWebViewPackage().versionName` vaut par exemple
     * « 120.0.6099.144 » pour le WebView Google.
     */
    fun chromeMajor(): Int = try {
        val pkg = android.webkit.WebView.getCurrentWebViewPackage()
        val m = Regex("(\\d{2,4})\\.\\d+\\.\\d+").find(pkg?.versionName ?: "")
        m?.groupValues?.get(1)?.toIntOrNull() ?: FALLBACK_CHROME_MAJOR
    } catch (t: Throwable) {
        FALLBACK_CHROME_MAJOR
    }

    /** Mode d'identité choisi par l'utilisateur (« desktop » par défaut). */
    fun identityMode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", IDENTITY_DESKTOP) ?: IDENTITY_DESKTOP

    fun setIdentityMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode).apply()
    }

    /** UA effectivement appliqué au WebView. */
    fun userAgentFor(ctx: Context): String {
        val major = chromeMajor()
        return when (identityMode(ctx)) {
            IDENTITY_ANDROID ->
                "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
                    "${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$major.0.0.0 Mobile Safari/537.36"
            else -> DESKTOP_UA
        }
    }

    /** Contexte applicatif du WebView (null avant le premier `ensure`). */
    fun appContextOrNull(): Context? = appContext

    /** La page signale une session expirée : la copie de secours est abandonnée. */
    fun onLoginRequired() {
        val ctx = appContext ?: return
        Cookies.forgetIfLoginRequired(ctx)
    }

    fun ensure(context: Context): WebView {
        webView?.let { attach(it); return it }
        val ctx = context.applicationContext
        appContext = ctx
        Engine.init(ctx.assets)

        val wv = WebView(ctx)
        @SuppressLint("SetJavaScriptEnabled")
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.userAgentString = userAgentFor(ctx)
        s.mediaPlaybackRequiresUserGesture = false
        @Suppress("DEPRECATION")
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportMultipleWindows(false)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        // si le magasin de cookies a été vidé, on remet la copie de réserve
        Cookies.restoreIfMissing(ctx)

        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // No WebView debugging in the NEMESIS variant: the authenticated
        // provider page must not expose a remote debugging endpoint.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(false)
        }

        // Hook réseau installé AVANT les scripts de la page (document-start).
        installDocumentStart(wv)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageLoading = true
                lastPageEventAt = android.os.SystemClock.elapsedRealtime()
                currentUrl = url ?: currentUrl
                rememberSafeUrl(url)
                AppState.pageUrl = currentUrl
                // repli si le document-start n'est pas supporté par le provider WebView
                if (isProvider(url)) view?.evaluateJavascript(Engine.asset("js/hook.js"), null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoading = false
                lastPageEventAt = android.os.SystemClock.elapsedRealtime()
                currentUrl = url ?: currentUrl
                rememberSafeUrl(url)
                AppState.pageUrl = currentUrl
                if (isProvider(url)) {
                    view?.evaluateJavascript(Engine.asset("js/hook.js"), null)
                    view?.evaluateJavascript(Engine.asset("js/adapter-deepseek.js"), null)
                    Cookies.save(appContext ?: ctx)
                }
                NemLog.i("dom", "page chargée : ${hostOf(currentUrl)}")
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                if(view != null && view === webView) {
                    NemLog.w("dom", "Processus WebView interrompu : reconstruction du navigateur")
                    (view.parent as? ViewGroup)?.removeView(view)
                    webView=null; pageLoading=false; currentUrl=lastSafeUrl; DomHealth.noteRestart()
                    try { view.destroy() } catch (_:Throwable) {}
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try { appContext?.let { ensure(it) } } catch(e:Throwable) { NemLog.w("dom", "Reconstruction WebView impossible : ${e.message}") }
                    }
                }
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            /**
             * Diagnostic en lecture seule : journalise UNE fois les en-têtes
             * envoyés au provider (User-Agent, X-Requested-With, Sec-CH-UA…).
             * Rien n'est intercepté ni modifié ici — `null` laisse la requête
             * partir telle quelle.
             */
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                try {
                    val url = request?.url ?: return null
                    if (headersLogged.compareAndSet(false, true) && isProvider(url.toString())) {
                        val h = request.requestHeaders ?: emptyMap()
                        val interesting = h.keys.filter {
                            it.startsWith("sec-ch", true) || it.startsWith("x-", true) ||
                                it.equals("user-agent", true) || it.equals("accept-language", true)
                        }.sorted()
                        NemLog.i("dom", "en-têtes provider : " + interesting.joinToString(", ") { k ->
                            k + "=" + (h[k] ?: "").take(60)
                        })
                    }
                } catch (_: Throwable) {
                }
                return null
            }
        }

        webView = wv
        AppState.webViewProvider = describeProvider()
        AppState.webViewIsGoogle = AppState.webViewProvider.contains("Google", true)
        NemLog.i("dom", "WebView provider : ${AppState.webViewProvider}")

        attach(wv)
        wv.loadUrl(currentUrl)
        return wv
    }

    /** Vrai si l'URL appartient au fournisseur de chat. */
    private fun isProvider(url: String?): Boolean = try {
        val h = java.net.URI(url ?: "").host ?: return false
        h == "chat.deepseek.com" || h.endsWith(".deepseek.com")
    } catch (t: Throwable) {
        false
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (t: Throwable) {
        url
    }

    /**
     * Le script est installé **uniquement pour l'origine du provider**.
     *
     * L'ancien `setOf("*")` injectait le hook dans TOUTES les origines visitées
     * par le WebView (CDN du fournisseur de PoW, redirection de connexion…).
     * Le hook modifie `window.fetch` et `XMLHttpRequest.prototype` : l'exécuter
     * hors du provider augmente la surface détectable sans aucun bénéfice.
     */
    private fun installDocumentStart(wv: WebView) {
        val script = Engine.asset("js/hook.js")
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(wv, script, PROVIDER_ORIGINS)
                NemLog.i("dom", "hook réseau : document-start (origine provider uniquement)")
            } else {
                NemLog.w("dom", "document-start indisponible → injection onPageStarted")
            }
        } catch (t: Throwable) {
            NemLog.w("dom", "document-start échoué : ${t.message}")
        }
    }

    /** Origines légitimes du fournisseur de chat. */
    private val PROVIDER_ORIGINS = setOf("https://chat.deepseek.com", "https://*.deepseek.com")

    private fun attach(wv: WebView) {
        val c = container ?: return
        if (wv.parent === c) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        c.addView(wv)
    }

    fun bindContainer(c: ViewGroup) {
        container = c
        webView?.let { attach(it) }
    }

    fun unbindContainer(expected: ViewGroup? = null) {
        if (expected != null && container !== expected) return
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        container = null
    }

    fun load(url: String) {
        pageLoading = true
        lastPageEventAt = android.os.SystemClock.elapsedRealtime()
        currentUrl = url
        AppState.pageUrl = url
        webView?.loadUrl(url)
    }

    fun reload() {
        webView?.reload()
    }

    /**
     * Redémarrage EXPLICITE du moteur DOM, uniquement sur action utilisateur.
     * Jamais appelé depuis un chemin de timeout : reconstruire la page sans
     * demande risquerait d'ouvrir ou restaurer une session du provider.
     * Cookies, URL sûre mémorisée et données sont conservés ; aucun message
     * n'est renvoyé et l'état « incertain » de la discussion reste intact.
     */
    fun restart() {
        main.post {
            val old = webView
            webView = null
            pageLoading = false
            (old?.parent as? ViewGroup)?.removeView(old)
            try { old?.destroy() } catch (_: Throwable) {}
            DomHealth.noteRestart()
            val ctx = appContext
            if (ctx != null) {
                try {
                    ensure(ctx)
                    NemLog.i("dom", "moteur DOM redémarré à la demande de l'utilisateur")
                } catch (t: Throwable) {
                    NemLog.w("dom", "Redémarrage DOM impossible : ${t.message}")
                }
            } else {
                NemLog.w("dom", "Redémarrage DOM impossible : contexte applicatif indisponible")
            }
        }
    }

    /** Version du provider WebView installé (Google WebView ou AOSP). */
    fun describeProvider(): String {
        return try {
            val pkg = WebView.getCurrentWebViewPackage()
            if (pkg == null) "inconnu"
            else "${pkg.packageName} ${pkg.versionName}"
        } catch (t: Throwable) {
            "inconnu (${t.message})"
        }
    }

    /** Adresse de la page courante, pour l'affichage. */
    fun pageTitle(): String = try {
        webView?.title ?: ""
    } catch (t: Throwable) {
        ""
    }
}
