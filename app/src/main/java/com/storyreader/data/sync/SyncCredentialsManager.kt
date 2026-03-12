package com.storyreader.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SyncCredentialsManager(private val prefs: SharedPreferences) {

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var appPassword: String?
        get() = prefs.getString(KEY_APP_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_APP_PASSWORD, value).apply()

    val hasCredentials: Boolean
        get() = !serverUrl.isNullOrBlank() && !username.isNullOrBlank() && !appPassword.isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_APP_PASSWORD = "app_password"

        fun create(context: Context): SyncCredentialsManager {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val prefs = EncryptedSharedPreferences.create(
                "sync_credentials",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return SyncCredentialsManager(prefs)
        }
    }
}
