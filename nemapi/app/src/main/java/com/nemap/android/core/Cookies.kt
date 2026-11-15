package com.nemap.android.core

import android.content.Context
import android.webkit.CookieManager

/**
 * Conservation de la session du provider dans les préférences de l'app.
 *
 * `CookieManager` persiste déjà les cookies sur le disque, mais ils sont perdus
 * si les données de l'application sont effacées (réinstallation, remise à zéro
 * de l'émulateur). On garde donc une copie de secours de la chaîne de cookies
 * et on la réinjecte au démarrage du WebView — **uniquement si elle complète
 * le magasin courant au lieu de le écraser**.
 *
 * Deux défauts corrigés ici (ils provoquaient des déconnexions répétées) :
 *
 *  1. la version précédente comparait les chaînes par LONGUEUR. Après une
 *     rotation de cookie côté serveur (le nouveau jeu de cookies est souvent
 *     plus court), la copie de secours était réinjectée par-dessus la session
 *     valide : le provider recevait un ancien identifiant de session, le
 *     considérer comme révoqué, et déconnectait le compte. On compare
 *     désormais les NOMS de cookies, jamais les longueurs.
 *  2. la copie était réinjectée sans condition d'âge. Elle est désormais
 *     limitée à [MAX_AGE_MS] et abandonnée si la page signale une session
 *     expirée.
 *
 * Ce n'est pas un contournement : la copie ne contient que des cookies déjà
 * présents dans l'app, et une session révoquée côté serveur reste révoquée.
 */
object Cookies {

    private const val PREFS = "nemapi_session"
    private const val KEY = "deepseek_cookie"
    private const val KEY_AT = "deepseek_cookie_at"
    private const val DOMAIN = "https://chat.deepseek.com/"

    /** Durée de vie maximale de la copie de secours (12 h). */
    const val MAX_AGE_MS = 12L * 60L * 60L * 1000L

    /** Cookies actuellement détenus par le WebView. */
    fun snapshot(): String = try {
        CookieManager.getInstance().getCookie(DOMAIN) ?: ""
    } catch (t: Throwable) {
        ""
    }

    /** Noms de cookies présents dans une chaîne « a=1; b=2 ». */
    private fun names(chain: String): Set<String> =
        chain.split(";").mapNotNull { part ->
            val token = part.trim()
            if (token.isEmpty() || !token.contains("=")) null else token.substringBefore("=")
        }.filter { it.isNotEmpty() }.toSet()

    /** Copie de secours dans les préférences (appelée périodiquement). */
    fun save(ctx: Context): Boolean {
        val current = snapshot()
        if (current.length < 40) return false
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY, "") == current) return false
        prefs.edit()
            .putString(KEY, current)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
        try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
        NemLog.i("dom", "session mise en réserve (${current.length} c.)")
        return true
    }

    fun saved(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    private fun savedAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)

    /**
     * Réinjecte la copie de secours si le magasin de cookies est vide, ou si
     * elle contient des cookies MANQUANTS. Un cookie déjà présent n'est jamais
     * écrasé : on ne réinjecte que ce qui manque.
     *
     * Rend `true` si une réinjection a eu lieu.
     */
    fun restoreIfMissing(ctx: Context): Boolean {
        val saved = saved(ctx)
        if (saved.isEmpty()) return false
        val at = savedAt(ctx)
        if (at > 0L && System.currentTimeMillis() - at > MAX_AGE_MS) {
            NemLog.w("dom", "copie de session abandonnée : trop ancienne")
            forget(ctx)
            return false
        }
        val current = snapshot()
        val currentNames = names(current)
        val missing = saved.split(";").map { it.trim() }
            .filter { it.contains("=") }
            .filter { part -> part.substringBefore("=") !in currentNames }
        if (missing.isEmpty()) return false
        return try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            missing.forEach { cm.setCookie(DOMAIN, it) }
            cm.flush()
            NemLog.i("dom", "session complétée depuis les préférences (${missing.size} c.)")
            true
        } catch (t: Throwable) {
            NemLog.w("dom", "réinjection impossible : ${t.message}")
            false
        }
    }

    /**
     * La page a signalé une session expirée : la copie de secours est
     * devenue inutile (et potentiellement nocive). On l'abandonne pour ne pas
     * réinjecter une session révoquée au prochain démarrage.
     */
    fun forgetIfLoginRequired(ctx: Context) {
        if (saved(ctx).isEmpty()) return
        forget(ctx)
        NemLog.w("dom", "copie de session abandonnée : le provider a refusé la session")
    }

    fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY).remove(KEY_AT).apply()
    }
}
