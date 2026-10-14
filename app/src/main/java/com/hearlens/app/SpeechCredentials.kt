package com.hearlens.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Personal test credentials stay on this phone, encrypted by Android Keystore; never in the APK. */
internal class SpeechCredentials(private val context:Context) {
    private val prefs=context.getSharedPreferences("speech_credentials",Context.MODE_PRIVATE)
    private val alias="HearLensSpeechCredentialV1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(value:String) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val encrypted=cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit(commit=true) { putString("iv",Base64.getEncoder().encodeToString(cipher.iv));putString("value",Base64.getEncoder().encodeToString(encrypted)) }
    }
    fun read():String {
        val encrypted=prefs.getString("value",null) ?: return ""
        return try {
            val iv=Base64.getDecoder().decode(prefs.getString("iv","")!!)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv)) }
            String(cipher.doFinal(Base64.getDecoder().decode(encrypted)),Charsets.UTF_8)
        } catch(_:Exception) { clear();"" }
    }
    fun clear() { prefs.edit(commit=true) { clear() } }
    /** Debug provisioning via adb run-as and stdin. Consumed once, never auto-connects or records. */
    fun consumeDebugProvision():String? {
        if(!BuildConfig.DEBUG)return null
        val file=File(context.filesDir,"speech-provision.json")
        if(!file.exists())return null
        return try {
            require(file.length()<=16384)
            val json=JSONObject(file.readText())
            val connection=AliyunConnection(json.getString("workspace"),json.getString("key"))
            save(connection.key)
            context.getSharedPreferences("speech_v7",Context.MODE_PRIVATE).edit(commit=true) { putString("workspace",connection.workspace);putInt("connection",0) }
            "直连凭据已配置并加密保存到本机；尚未连接或录音。"
        } catch(_:Exception) { "凭据导入失败，请在本页重新填写。" }
        finally { file.delete() }
    }
}
