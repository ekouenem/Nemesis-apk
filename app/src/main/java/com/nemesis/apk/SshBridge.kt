package com.nemesis.apk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Password encrypted by Android Keystore; host key is pinned to a supplied fingerprint. */
object SshBridge {
    private lateinit var app: Context
    private const val alias = "nemesis_ssh_password"
    fun init(context: Context) { app = context.applicationContext }
    private fun prefs() = app.getSharedPreferences("ssh_executor", Context.MODE_PRIVATE)
    fun details(): JSONObject = JSONObject().put("host",prefs().getString("host", ""))
        .put("port",prefs().getInt("port",22)).put("user",prefs().getString("user", ""))
        .put("fingerprint",prefs().getString("fingerprint", ""))
        .put("configured", !prefs().getString("secret", "").isNullOrBlank())
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    fun save(host: String, port: Int, user: String, fingerprint: String, password: String) {
        require(host.isNotBlank() && user.isNotBlank() && port in 1..65535) { "Hôte, port ou utilisateur invalide" }
        val fp = fingerprint.trim()
        require(fp.startsWith("SHA256:") && fp.length in 30..90) { "Empreinte SHA256 du serveur requise" }
        require(password.isNotEmpty()) { "Mot de passe SSH requis" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs().edit().putString("host",host.trim()).putInt("port",port).putString("user",user.trim())
            .putString("fingerprint",fp.trimEnd('='))
            .putString("secret", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
    private fun password(): String {
        val raw = Base64.decode(prefs().getString("secret", ""), Base64.NO_WRAP)
        require(raw.size > 12) { "Mot de passe SSH non configuré" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0,12)))
        return String(cipher.doFinal(raw.copyOfRange(12,raw.size)), Charsets.UTF_8)
    }
    fun clear() { prefs().edit().clear().apply() }

    @JvmStatic fun execute(command: String, timeoutSeconds: Int): String {
        val config = details(); var session: com.jcraft.jsch.Session? = null
        var channel: com.jcraft.jsch.ChannelExec? = null
        return try {
            require(config.optBoolean("configured")) { "Configurez d'abord un serveur SSH ou Termux dans Réglages." }
            require(command.isNotBlank() && command.length <= 1_000) { "Commande vide ou trop longue" }
            val fingerprint = config.getString("fingerprint").trimEnd('=')
            val jsch = JSch()
            jsch.hostKeyRepository = object : HostKeyRepository {
                override fun check(host: String?, key: ByteArray?): Int {
                    if (key == null) return HostKeyRepository.CHANGED
                    val actual = "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP or Base64.NO_PADDING)
                    return if (MessageDigest.isEqual(actual.toByteArray(), fingerprint.toByteArray())) HostKeyRepository.OK else HostKeyRepository.CHANGED
                }
                override fun add(hostkey: HostKey?, ui: UserInfo?) {}
                override fun remove(host: String?, type: String?) {}
                override fun remove(host: String?, type: String?, key: ByteArray?) {}
                override fun getKnownHostsRepositoryID(): String = "NEMESIS pinned SHA256"
                override fun getHostKey(): Array<HostKey> = emptyArray()
                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }
            session = jsch.getSession(config.getString("user"), config.getString("host"), config.getInt("port"))
            session.setPassword(password()); session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            session.connect(15_000)
            channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand(command)
            val bytes = ByteArrayOutputStream()
            val capped = object : OutputStream() {
                @Synchronized override fun write(b: Int) { if (bytes.size() < 100_000) bytes.write(b) }
                @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
                    val keep = minOf(len, 100_000 - bytes.size()); if (keep > 0) bytes.write(b, off, keep)
                }
            }
            channel.setOutputStream(capped); channel.setErrStream(capped); channel.connect(15_000)
            val until = System.currentTimeMillis() + timeoutSeconds.coerceIn(1,300) * 1000L
            while (!channel.isClosed && System.currentTimeMillis() < until) Thread.sleep(100)
            if (!channel.isClosed) JSONObject().put("success",false).put("error","Délai SSH dépassé ; la commande distante peut continuer.")
            else JSONObject().put("success",channel.exitStatus == 0).put("exit_code",channel.exitStatus)
                .put("output", bytes.toString("UTF-8")).put("truncated", bytes.size() >= 100_000)
        } catch (e: Exception) {
            JSONObject().put("success",false).put("error", "SSH : " + (e.message ?: e.javaClass.simpleName))
        } finally { channel?.disconnect(); session?.disconnect() }
        .toString()
    }
}
