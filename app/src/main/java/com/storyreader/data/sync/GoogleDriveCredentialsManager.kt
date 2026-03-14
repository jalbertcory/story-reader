package com.storyreader.data.sync

import android.content.Context
import android.content.SharedPreferences

class GoogleDriveCredentialsManager(
    private val prefs: SharedPreferences
) {

    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_EMAIL, value).apply()

    var accountDisplayName: String?
        get() = prefs.getString(KEY_ACCOUNT_DISPLAY_NAME, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_DISPLAY_NAME, value).apply()

    val hasAccount: Boolean
        get() = !accountEmail.isNullOrBlank()

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
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_ACCOUNT_DISPLAY_NAME = "account_display_name"

        fun create(context: Context): GoogleDriveCredentialsManager {
            val prefs = context.getSharedPreferences("google_drive_credentials", Context.MODE_PRIVATE)
            return GoogleDriveCredentialsManager(prefs)
        }
    }
}
