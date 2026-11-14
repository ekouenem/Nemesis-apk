package com.nemesis.apk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Narrow Android-native computer-use bridge. Arbitrary third-party services are intentionally not startable. */
object AndroidComputerBridge {
    @Volatile private var app: Context? = null
    @JvmStatic fun init(context: Context) { app = context.applicationContext }

    @JvmStatic fun accessibilityEnabled(): Boolean {
        val context = app ?: return false
        return try {
            val enabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (!enabled) return false
            val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            services.split(':').any { it.equals(ComponentName(context, NemesisAccessibilityService::class.java).flattenToString(), true) }
        } catch (_: Exception) { false }
    }

    @JvmStatic fun openAccessibilitySettings(): String {
        val context = app ?: return JSONObject().put("success", false).put("error", "Application non initialisée").toString()
        return try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            JSONObject().put("success", true).put("opened", "accessibility_settings").toString()
        } catch (_: Exception) { JSONObject().put("success", false).put("error", "Réglages Accessibilité indisponibles").toString() }
    }

    /** JSON-only API called by the Python agent. */
    @JvmStatic fun execute(action: String, argumentsJson: String): String {
        val args = try { JSONObject(argumentsJson) } catch (_: Exception) { JSONObject() }
        val context = app ?: return error("Pont Android non initialisé")
        return try {
            when (action) {
                "list_apps" -> listApps(context, args.optString("query", ""))
                "launch_app" -> launchApp(context, args.optString("package", ""))
                "open_url" -> openUrl(context,args.optString("url", ""))
                "share_text" -> shareText(context,args.optString("text", ""),args.optString("title", "Partager avec"))
                "accessibility_status" -> JSONObject().put("success", true).put("enabled", accessibilityEnabled()).toString()
                "ui_snapshot", "click_text", "long_click_text", "type_text", "scroll", "tap", "swipe", "back", "home", "recents", "notifications" ->
                    NemesisAccessibilityService.perform(action, args).toString()
                else -> error("Action Android inconnue")
            }
        } catch (_: SecurityException) { error("Action refusée par Android ou par l’application cible") }
          catch (_: Exception) { error("Action Android impossible") }
    }

    private fun listApps(context: Context, query: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try { pm.queryIntentActivities(launcher, 0) } catch (_: Exception) { emptyList() }
        val needle = query.trim().take(80)
        val rows = apps.asSequence().mapNotNull { resolve ->
            val pkg = resolve.activityInfo?.packageName ?: return@mapNotNull null
            val label = try { resolve.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            if (needle.isNotEmpty() && !label.contains(needle, true) && !pkg.contains(needle, true)) return@mapNotNull null
            JSONObject().put("label", label.take(100)).put("package", pkg).put("activity", resolve.activityInfo.name)
        }.distinctBy { it.optString("package") }.sortedBy { it.optString("label").lowercase() }.take(200)
        val result = JSONArray(); rows.forEach { result.put(it) }
        return JSONObject().put("success", true).put("apps", result).put("count", result.length()).toString()
    }

    private fun openUrl(context:Context,raw:String):String {
        val value=raw.trim().take(2048);val uri=try{android.net.Uri.parse(value)}catch(_:Exception){null}
        if(uri==null || uri.scheme?.lowercase() !in setOf("http","https") || uri.host.isNullOrBlank() || !uri.userInfo.isNullOrBlank()) return JSONObject().put("success",false).put("error","URL publique HTTP(S) requise").toString()
        val intent=Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(intent.resolveActivity(context.packageManager)==null)return JSONObject().put("success",false).put("error","Aucun navigateur ou gestionnaire compatible").toString()
        context.startActivity(intent)
        return JSONObject().put("success",true).put("action","open_url").put("host",uri.host).put("opened",true).toString()
    }

    private fun shareText(context:Context,text:String,title:String):String {
        val value=text.trim();if(value.isEmpty()||value.length>5000)return JSONObject().put("success",false).put("error","Texte requis (5 000 caractères maximum)").toString()
        val send=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,value)
        context.startActivity(Intent.createChooser(send,title.take(80)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject().put("success",true).put("action","share_text").put("draft_opened",true).put("sent",false).toString()
    }

    private fun launchApp(context: Context, packageName: String): String {
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return error("Nom de package invalide")
        val intent = try { context.packageManager.getLaunchIntentForPackage(packageName) } catch (_: Exception) { null }
            ?: return error("Application absente ou sans écran de lancement : $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("success", true).put("package", packageName).put("launched", true).toString()
    }

    private fun error(message: String) = JSONObject().put("success", false).put("error", message).toString()
}
