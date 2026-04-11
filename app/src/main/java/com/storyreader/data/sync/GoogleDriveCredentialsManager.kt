package com.storyreader.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class GoogleDriveCredentialsManager(
    private val prefs: SharedPreferences
) {

    var isAuthorized: Boolean
        get() = prefs.getBoolean(KEY_IS_AUTHORIZED, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_AUTHORIZED, value) }

    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        set(value) = prefs.edit { putString(KEY_ACCOUNT_EMAIL, value) }

    var accountDisplayName: String?
        get() = prefs.getString(KEY_ACCOUNT_DISPLAY_NAME, null)
        set(value) = prefs.edit { putString(KEY_ACCOUNT_DISPLAY_NAME, value) }

    val hasAccount: Boolean
        get() = isAuthorized || !accountEmail.isNullOrBlank() || !accountDisplayName.isNullOrBlank()

    fun displayLabel(): String? {
        val email = accountEmail
        val name = accountDisplayName
        return when {
            !name.isNullOrBlank() && !email.isNullOrBlank() -> "$name ($email)"
            !email.isNullOrBlank() -> email
            !name.isNullOrBlank() -> name
            else -> null
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val KEY_IS_AUTHORIZED = "is_authorized"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_ACCOUNT_DISPLAY_NAME = "account_display_name"

        fun create(context: Context): GoogleDriveCredentialsManager {
            val backing = context.getSharedPreferences("google_drive_credentials", Context.MODE_PRIVATE)
            return GoogleDriveCredentialsManager(KeystoreEncryptedPrefs(backing))
        }
    }
}
