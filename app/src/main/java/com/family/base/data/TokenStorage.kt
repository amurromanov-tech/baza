package com.family.base.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.family.base.Config

class TokenStorage(context: Context) {

    private val prefs: SharedPreferences

    init {
        prefs = try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "secure_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    // ========== ТОКЕНЫ ==========

    fun saveAccessToken(token: String) {
        prefs.edit().putString("access_token", token).apply()
    }

    fun getAccessToken(): String? {
        val token = prefs.getString("access_token", null)
        if (token != null) {
            android.util.Log.d("TokenStorage", "Token from storage: $token")
        }
        return token
    }

    fun saveRefreshToken(token: String?) {
        token?.let {
            prefs.edit().putString("refresh_token", it).apply()
        }
    }

    fun getRefreshToken(): String? {
        return prefs.getString("refresh_token", null)
    }

    fun saveIdToken(token: String?) {
        token?.let {
            prefs.edit().putString("id_token", it).apply()
        }
    }

    fun getIdToken(): String? {
        return prefs.getString("id_token", null)
    }

    // ========== ПУБЛИЧНЫЙ КЛЮЧ ==========

    fun savePublicKey(key: String) {
        prefs.edit().putString(Config.PREF_PUBLIC_KEY, key).apply()
    }

    fun getPublicKey(): String? {
        return prefs.getString(Config.PREF_PUBLIC_KEY, null)
    }

    // ========== ИМЯ ПАПКИ ==========

    fun saveFolderName(name: String) {
        prefs.edit().putString("folder_name", name).apply()
    }

    fun getFolderName(): String {
        return prefs.getString("folder_name", "BAZA") ?: "BAZA"
    }

    // ========== ОБЩАЯ ПАПКА (добавлено) ==========

    fun saveSharedFolderLink(link: String) {
        prefs.edit().putString("shared_folder_link", link).apply()
    }

    fun getSharedFolderLink(): String? {
        return prefs.getString("shared_folder_link", null)
    }

    fun saveSharedFolderName(name: String) {
        prefs.edit().putString("shared_folder_name", name).apply()
    }

    fun getSharedFolderName(): String? {
        return prefs.getString("shared_folder_name", null)
    }

    // ========== ИНФОРМАЦИЯ О ПОЛЬЗОВАТЕЛЕ ==========

    fun saveUserInfo(email: String, displayName: String) {
        prefs.edit().apply {
            putString("user_email", email)
            putString("user_display_name", displayName)
            apply()
        }
    }

    fun getUserEmail(): String? {
        return prefs.getString("user_email", null)
    }

    fun getUserDisplayName(): String? {
        return prefs.getString("user_display_name", null)
    }

    // ========== ОБНОВЛЕНИЕ ТОКЕНА ==========

    fun refreshAccessToken(): String? {
        val refreshToken = getRefreshToken()
        if (refreshToken == null) {
            android.util.Log.e("TokenStorage", "No refresh token available")
            return null
        }

        return try {
            val client = okhttp3.OkHttpClient()
            val formBody = okhttp3.FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", Config.CLIENT_ID)
                .build()

            val request = okhttp3.Request.Builder()
                .url("https://oauth.yandex.ru/token")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = org.json.JSONObject(responseBody)
                val newAccessToken = json.optString("access_token", null)
                val newRefreshToken = json.optString("refresh_token", null)

                if (newAccessToken != null) {
                    saveAccessToken(newAccessToken)
                    if (newRefreshToken != null) {
                        saveRefreshToken(newRefreshToken)
                    }
                    android.util.Log.d("TokenStorage", "Token refreshed successfully")
                    return newAccessToken
                }
            }

            android.util.Log.e("TokenStorage", "Failed to refresh token: ${response.code}")
            null
        } catch (e: Exception) {
            android.util.Log.e("TokenStorage", "Error refreshing token", e)
            null
        }
    }

    // ========== ОЧИСТКА ==========

    fun clear() {
        prefs.edit().clear().apply()
    }
}
