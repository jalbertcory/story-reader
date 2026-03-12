package com.storyreader.data.sync

import android.content.Context
import android.content.SharedPreferences

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
            val backing = context.getSharedPreferences("sync_credentials", Context.MODE_PRIVATE)
            return SyncCredentialsManager(KeystoreEncryptedPrefs(backing))
        }
    }
}
