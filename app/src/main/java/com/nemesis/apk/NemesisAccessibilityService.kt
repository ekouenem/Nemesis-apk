package com.nemesis.apk

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Optional user-enabled service. It never reads password fields or captures screens. */
class NemesisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Snapshot is pull-based, only on an explicit agent tool call. */ }
    override fun onInterrupt() { }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    private fun performOnMain(action: String, args: JSONObject): JSONObject {
        val root = rootInActiveWindow
        if (action == "ui_snapshot") {
            if (root == null) return failure("Aucune fenêtre Android active")
            try {
                val nodes = JSONArray(); collect(root, nodes, 0, 160)
                return JSONObject().put("success", true).put("nodes", nodes).put("count", nodes.length())
                    .put("package", root.packageName?.toString().orEmpty())
            } finally { try { root.recycle() } catch (_: Exception) { } }
        }
        if (action in listOf("back", "home", "recents", "notifications")) {
            val global = when (action) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                else -> GLOBAL_ACTION_NOTIFICATIONS
            }
            return JSONObject().put("success", performGlobalAction(global)).put("action", action)
        }
        if (root == null) return failure("Aucune fenêtre Android active")
        return try {
            when (action) {
                "click_text" -> clickText(root, args.optString("text", ""))
                "long_click_text" -> longClickText(root,args.optString("text", ""))
                "type_text" -> typeText(root, args.optString("text", ""))
                "scroll" -> scroll(root, args.optString("direction", "down"))
                "tap" -> gesture(args.optDouble("x", -1.0), args.optDouble("y", -1.0), null, null, 80)
                "swipe" -> gesture(args.optDouble("x1", -1.0), args.optDouble("y1", -1.0), args.optDouble("x2", -1.0), args.optDouble("y2", -1.0), args.optLong("duration_ms", 350).coerceIn(100, 1500))
                else -> failure("Action Accessibilité inconnue")
            }
        } finally { try { root.recycle() } catch (_: Exception) { } }
    }

    private fun clickText(root: AccessibilityNodeInfo, query: String): JSONObject {
        val text = query.trim().take(160)
        if (text.isEmpty()) return failure("Texte cible requis")
        val candidates = try { root.findAccessibilityNodeInfosByText(text).orEmpty() } catch (_: Exception) { emptyList() }
        val match = candidates.firstOrNull { !it.isPassword && (it.text?.toString()?.equals(text, true) == true || it.contentDescription?.toString()?.equals(text, true) == true) }
            ?: candidates.firstOrNull { !it.isPassword }
            ?: return failure("Élément introuvable : $text")
        var clickable: AccessibilityNodeInfo? = match
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        val target = clickable ?: match
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        candidates.forEach { try { it.recycle() } catch (_: Exception) { } }
        return JSONObject().put("success", ok).put("action", "click_text").put("matched", text)
    }

    private fun longClickText(root:AccessibilityNodeInfo,query:String):JSONObject {
        val text=query.trim().take(160);if(text.isEmpty())return failure("Texte cible requis")
        val candidates=try{root.findAccessibilityNodeInfosByText(text).orEmpty()}catch(_:Exception){emptyList()}
        val match=candidates.firstOrNull{!it.isPassword && (it.text?.toString()?.equals(text,true)==true || it.contentDescription?.toString()?.equals(text,true)==true)}
            ?:candidates.firstOrNull{!it.isPassword}
        if(match==null){candidates.forEach{try{it.recycle()}catch(_:Exception){}};return failure("Élément introuvable : $text")}
        val ok=match.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        candidates.forEach{try{it.recycle()}catch(_:Exception){}}
        return JSONObject().put("success",ok).put("action","long_click_text").put("matched",text)
    }

    private fun typeText(root: AccessibilityNodeInfo, value: String): JSONObject {
        if (value.length > 2000) return failure("Saisie limitée à 2 000 caractères")
        var target = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        if (target == null || !target.isEditable) {
            target?.let { try { it.recycle() } catch (_: Exception) { } }
            target = findEditable(root, 0)
        }
        val node = target ?: return failure("Aucun champ éditable actif")
        if (node.isPassword) { try { node.recycle() } catch (_: Exception) { }; return failure("Refus de saisir dans un champ mot de passe") }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        try { node.recycle() } catch (_: Exception) { }
        return JSONObject().put("success", ok).put("action", "type_text").put("characters", value.length)
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 24) return null
        if (node.isEditable && !node.isPassword && node.isEnabled) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child, depth + 1)
            try { child.recycle() } catch (_: Exception) { }
            if (found != null) return found
        }
        return null
    }

    private fun scroll(root: AccessibilityNodeInfo, direction: String): JSONObject {
        if (direction !in setOf("up", "down", "forward", "backward")) return failure("Direction attendue : up/down")
        val nodes = mutableListOf<AccessibilityNodeInfo>(); findScrollable(root, nodes, 0)
        val target = nodes.firstOrNull() ?: return failure("Aucun élément défilable visible")
        val action = if (direction == "up" || direction == "backward") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val ok = target.performAction(action)
        nodes.forEach { try { it.recycle() } catch (_: Exception) { } }
        return JSONObject().put("success", ok).put("action", "scroll").put("direction", direction)
    }

    private fun findScrollable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 24 || out.size >= 20) return
        if (node.isScrollable && node.isEnabled) out.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child, out, depth + 1)
            try { child.recycle() } catch (_: Exception) { }
            if (out.size >= 20) return
        }
    }

    private fun gesture(x1: Double, y1: Double, x2: Double?, y2: Double?, duration: Long): JSONObject {
        val width = resources.displayMetrics.widthPixels.toDouble(); val height = resources.displayMetrics.heightPixels.toDouble()
        if (x1 !in 0.0..width || y1 !in 0.0..height) return failure("Coordonnées hors écran")
        if (x2 != null && y2 != null && (x2 !in 0.0..width || y2 !in 0.0..height)) return failure("Coordonnées hors écran")
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); if (x2 != null && y2 != null) lineTo(x2.toFloat(), y2.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, if (x2 == null) 80 else duration)
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return JSONObject().put("success", accepted).put("action", if (x2 == null) "tap" else "swipe")
    }

    private fun collect(node: AccessibilityNodeInfo, out: JSONArray, depth: Int, cap: Int) {
        if (depth > 20 || out.length() >= cap) return
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val row = JSONObject().put("class", node.className?.toString()?.take(100).orEmpty())
            .put("view_id", node.viewIdResourceName?.take(160).orEmpty())
            .put("description", node.contentDescription?.toString()?.take(200).orEmpty())
            .put("clickable", node.isClickable).put("editable", node.isEditable).put("scrollable", node.isScrollable)
            .put("enabled", node.isEnabled).put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
        if (!node.isPassword) row.put("text", node.text?.toString()?.take(200).orEmpty()) else row.put("text", "[masqué: champ sécurisé]")
        out.put(row)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1, cap)
            try { child.recycle() } catch (_: Exception) { }
            if (out.length() >= cap) break
        }
    }

    private fun failure(message: String) = JSONObject().put("success", false).put("error", message)

    companion object {
        @Volatile private var instance: NemesisAccessibilityService? = null
        private val main = Handler(Looper.getMainLooper())
        @JvmStatic fun perform(action: String, args: JSONObject): JSONObject {
            val service = instance ?: return JSONObject().put("success", false).put("error", "Contrôle d’interface désactivé. Activez le service NEMESIS dans les réglages Accessibilité Android.").put("accessibility_required", true)
            if (Looper.myLooper() == Looper.getMainLooper()) return service.performOnMain(action, args)
            val task = FutureTask(Callable { service.performOnMain(action, args) })
            main.post(task)
            return try { task.get(8, TimeUnit.SECONDS) }
            catch (_: Exception) { JSONObject().put("success", false).put("error", "Délai dépassé pendant l’action Android") }
        }
    }
}
