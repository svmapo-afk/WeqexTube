package com.github.libretube.oauth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object YoutubeOAuthManager {
    private const val CLIENT_ID = "208620062506-p8huru5oe2lrcn8a72v25dsu3i543aag.apps.googleusercontent.com"
    private const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly openid profile"
    private const val PREFS = "weqextube_youtube_oauth"
    private const val KEY_ALIAS = "weqextube_youtube_token_key"
    private const val ACCESS_TOKEN = "access_token"
    private const val REFRESH_TOKEN = "refresh_token"
    private const val EXPIRES_AT = "expires_at"

    private lateinit var context: Context
    private val lock = Any()

    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    )

    sealed interface PollResult {
        data object Pending : PollResult
        data class Success(val accessToken: String) : PollResult
        data class Failure(val message: String) : PollResult
    }

    fun initialize(value: Context) {
        context = value.applicationContext
    }

    fun isSignedIn(): Boolean = ::context.isInitialized && encryptedGet(REFRESH_TOKEN) != null

    suspend fun requestDeviceAuthorization(): DeviceAuthorization = withContext(Dispatchers.IO) {
        val json = postForm(DEVICE_URL, mapOf("client_id" to CLIENT_ID, "scope" to SCOPE))
        DeviceAuthorization(
            json.getString("device_code"),
            json.getString("user_code"),
            json.optString("verification_url", json.optString("verification_uri", "https://www.google.com/device")),
            json.optLong("expires_in", 1800L),
            json.optLong("interval", 5L),
        )
    }

    suspend fun waitForAuthorization(device: DeviceAuthorization): PollResult = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000L
        var interval = device.intervalSeconds.coerceAtLeast(5L)
        while (System.currentTimeMillis() < deadline) {
            val json = postFormAllowError(TOKEN_URL, mapOf(
                "client_id" to CLIENT_ID,
                "device_code" to device.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ))
            when (val error = json.optString("error")) {
                "" -> {
                    saveTokens(json)
                    return@withContext PollResult.Success(json.getString("access_token"))
                }
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5L
                "access_denied" -> return@withContext PollResult.Failure("Доступ отклонён в Google.")
                "expired_token" -> return@withContext PollResult.Failure("Код истёк. Получите новый код.")
                else -> return@withContext PollResult.Failure(json.optString("error_description", error.ifBlank { "Ошибка Google OAuth" }))
            }
            delay(interval * 1000L)
        }
        PollResult.Failure("Код истёк. Получите новый код.")
    }

    fun authorizationHeader(): String? = synchronized(lock) {
        if (!::context.isInitialized) return@synchronized null
        val expiresAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(EXPIRES_AT, 0L)
        val current = encryptedGet(ACCESS_TOKEN)
        if (current != null && System.currentTimeMillis() < expiresAt - 60_000L) {
            return@synchronized "Bearer $current"
        }
        val refreshToken = encryptedGet(REFRESH_TOKEN) ?: return@synchronized null
        val json = runCatching {
            postForm(TOKEN_URL, mapOf(
                "client_id" to CLIENT_ID,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
            ))
        }.getOrNull() ?: return@synchronized null
        saveTokens(json, refreshToken)
        json.optString("access_token").takeIf { it.isNotBlank() }?.let { "Bearer $it" }
    }

    fun signOut() {
        if (::context.isInitialized) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun saveTokens(json: JSONObject, existingRefreshToken: String? = null) {
        val accessToken = json.getString("access_token")
        val refreshToken = json.optString("refresh_token").ifBlank { existingRefreshToken.orEmpty() }
        encryptedPut(ACCESS_TOKEN, accessToken)
        if (refreshToken.isNotBlank()) encryptedPut(REFRESH_TOKEN, refreshToken)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(EXPIRES_AT, System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L)
            .apply()
    }

    private fun postForm(url: String, values: Map<String, String>): JSONObject {
        val json = postFormAllowError(url, values)
        if (json.has("error")) throw IllegalStateException(json.optString("error_description", json.optString("error", "OAuth error")))
        return json
    }

    private fun postFormAllowError(url: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, StandardCharsets.UTF_8.name()) + "=" +
                URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        }.toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        connection.outputStream.use { it.write(body) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return JSONObject(response.ifBlank { "{\"error\":\"empty_response\"}" })
    }

    private fun encryptedPut(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, packed).apply()
    }

    private fun encryptedGet(key: String): String? = runCatching {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        val parts = packed.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
}
