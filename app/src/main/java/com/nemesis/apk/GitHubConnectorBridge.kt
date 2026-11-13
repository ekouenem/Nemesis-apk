package com.nemesis.apk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Fine-grained GitHub token stays encrypted at rest and is never exposed to UI/logs. */
object GitHubConnectorBridge {
    private lateinit var app: Context
    private const val KEY_ALIAS = "nemesis_github_connector"
    fun init(context: Context) { app = context.applicationContext }
    private fun prefs() = app.getSharedPreferences("connector_github", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS,null) as? SecretKey)?.let { return it }
        val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    private fun encrypt(value:String):String {
        val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(c.iv+c.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    private fun decrypt(value:String):String {
        if(value.isBlank()) return ""
        val raw=Base64.decode(value,Base64.NO_WRAP); require(raw.size>12)
        val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(0,12)))
        return String(c.doFinal(raw.copyOfRange(12,raw.size)),Charsets.UTF_8)
    }
    fun save(username:String, token:String) {
        val normalizedUser=username.trim()
        val normalizedToken=token.trim()
        require(normalizedUser.matches(Regex("[A-Za-z0-9-]{1,39}"))) { "Nom GitHub invalide" }
        require(normalizedToken.length in 20..500 && (normalizedToken.startsWith("github_pat_") || normalizedToken.startsWith("ghp_"))) {
            "Colle un token GitHub valide (fine-grained github_pat_ ou classic ghp_)."
        }
        check(prefs().edit().putString("username",normalizedUser).putString("token",encrypt(normalizedToken)).commit())
    }
    fun username():String = prefs().getString("username","") ?: ""
    @JvmStatic fun token():String = decrypt(prefs().getString("token","") ?: "")
    fun configured():Boolean = username().isNotBlank() && prefs().getString("token","").orEmpty().isNotBlank()
    fun status():String = JSONObject().put("configured",configured()).put("username",username()).put("account",username()).toString()
    fun clear() { check(prefs().edit().clear().commit()) { "Impossible d'effacer GitHub" } }
}
