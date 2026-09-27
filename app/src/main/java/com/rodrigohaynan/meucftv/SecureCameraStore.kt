package com.rodrigohaynan.meucftv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureCameraStore(context: Context) {

    private val prefs = context.getSharedPreferences("camera_config", Context.MODE_PRIVATE)

    fun load(): CameraConfig = CameraConfig(
        name = prefs.getString("name", "Garagem") ?: "Garagem",
        host = prefs.getString("host", "") ?: "",
        rtspPort = prefs.getInt("rtsp_port", 554),
        onvifPort = prefs.getInt("onvif_port", 5000),
        rtspPath = prefs.getString("rtsp_path", "/onvif1") ?: "/onvif1",
        rtspUser = prefs.getString("rtsp_user", "admin") ?: "admin",
        onvifUser = prefs.getString("onvif_user", "administrator") ?: "administrator",
        password = decrypt(prefs.getString("password", null))
    )

    fun save(config: CameraConfig) {
        prefs.edit()
            .putString("name", config.name)
            .putString("host", config.host.trim())
            .putInt("rtsp_port", config.rtspPort)
            .putInt("onvif_port", config.onvifPort)
            .putString("rtsp_path", normalizePath(config.rtspPath))
            .putString("rtsp_user", config.rtspUser)
            .putString("onvif_user", config.onvifUser)
            .putString("password", encrypt(config.password))
            .apply()
    }

    private fun normalizePath(path: String): String =
        if (path.startsWith("/")) path else "/$path"

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$iv:$payload"
    }

    private fun decrypt(stored: String?): String {
        if (stored.isNullOrBlank() || !stored.contains(":")) return ""

        return runCatching {
            val (ivText, payloadText) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val payload = Base64.decode(payloadText, Base64.NO_WRAP)

            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, iv)
            )

            String(cipher.doFinal(payload), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "meucftv_camera_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
