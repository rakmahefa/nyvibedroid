package com.nyvibe.droid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class ChatGptCredentials(
    val clientId: String,
    val subject: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val scopes: Set<String>,
    val expiresAtEpochSeconds: Long
)

internal class ChatGptCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "chatgpt_cloud_settings",
        Context.MODE_PRIVATE
    )

    fun load(): ChatGptCredentials? {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val encodedIv = preferences.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            val json = JSONObject(
                String(
                    cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                    Charsets.UTF_8
                )
            )
            val scopesJson = json.optJSONArray("scopes") ?: JSONArray()
            val scopes = buildSet {
                for (index in 0 until scopesJson.length()) {
                    scopesJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            ChatGptCredentials(
                clientId = json.getString("client_id"),
                subject = json.getString("subject"),
                email = json.optString("email"),
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                idToken = json.getString("id_token"),
                scopes = scopes,
                expiresAtEpochSeconds = json.getLong("expires_at")
            )
        }.getOrElse {
            clear()
            null
        }
    }

    fun save(credentials: ChatGptCredentials) {
        val scopesJson = JSONArray()
        credentials.scopes.sorted().forEach(scopesJson::put)
        val plaintext = JSONObject()
            .put("client_id", credentials.clientId)
            .put("subject", credentials.subject)
            .put("email", credentials.email)
            .put("access_token", credentials.accessToken)
            .put("refresh_token", credentials.refreshToken)
            .put("id_token", credentials.idToken)
            .put("scopes", scopesJson)
            .put("expires_at", credentials.expiresAtEpochSeconds)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun loadOrCreateHostId(): String {
        val existing = preferences.getString(KEY_HOST_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "urn:uuid:" + UUID.randomUUID()
        preferences.edit().putString(KEY_HOST_ID, generated).apply()
        return generated
    }

    fun clear() {
        preferences.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "nyvibedroid_chatgpt_plan"
        const val KEY_IV = "oauth_token_iv"
        const val KEY_CIPHERTEXT = "oauth_credentials_ciphertext"
        const val KEY_HOST_ID = "oauth_agent_host_id"
    }
}
