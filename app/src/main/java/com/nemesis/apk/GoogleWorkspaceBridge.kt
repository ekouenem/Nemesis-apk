package com.nemesis.apk

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import org.json.JSONObject

/** Google account metadata only; short-lived API tokens are refreshed by GoogleAuthUtil. */
object GoogleWorkspaceBridge {
    private lateinit var app:Context
    fun init(context:Context) { app=context.applicationContext }
    private fun prefs()=app.getSharedPreferences("connector_google_workspace",Context.MODE_PRIVATE)
    fun saveAccount(email:String) {
        require(email.contains("@") && email.length<=254) { "Compte Google invalide" }
        check(prefs().edit().putString("email",email).commit())
    }
    fun email():String=prefs().getString("email","") ?: ""
    fun configured():Boolean=email().isNotBlank()
    fun status():String=JSONObject().put("configured",configured()).put("email",email()).put("account",email()).toString()
    /** Called from the agent worker thread only; never log or return the token in model-facing output. */
    @JvmStatic fun accessToken():String {
        val account=email(); require(account.isNotBlank()) { "Connecte d'abord Google Workspace dans Paramètres." }
        val scopes=listOf(
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/drive.file",
            "https://www.googleapis.com/auth/documents",
            "https://www.googleapis.com/auth/spreadsheets"
        ).joinToString(" ")
        return GoogleAuthUtil.getToken(app,Account(account,"com.google"),"oauth2:$scopes")
    }
    fun clear() { prefs().edit().remove("email").commit() }
}
