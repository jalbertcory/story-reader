package com.storyreader.data.catalog

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.storyreader.data.sync.KeystoreEncryptedPrefs

class OpdsCredentialsManager(
    private val prefs: SharedPreferences
) {

    var url: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit { putString(KEY_URL, value) }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit { putString(KEY_USERNAME, value) }

    var password: String?
        get() = prefs.getString(KEY_PASSWORD, null)
        set(value) = prefs.edit { putString(KEY_PASSWORD, value) }

    var isStoryManagerBackend: Boolean
        get() = prefs.getBoolean(KEY_IS_STORY_MANAGER_BACKEND, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_STORY_MANAGER_BACKEND, value) }

    fun currentCredentials(): OpdsCredentials? {
        val baseUrl = url?.trim().orEmpty()
        if (baseUrl.isBlank()) return null
        val storyManager = isStoryManagerBackend
        return OpdsCredentials(
            baseUrl = baseUrl,
            username = if (storyManager) STORY_MANAGER_USERNAME else username.orEmpty(),
            password = password.orEmpty(),
            isStoryManagerBackend = storyManager
        )
    }

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_IS_STORY_MANAGER_BACKEND = "is_story_manager_backend"
        const val STORY_MANAGER_USERNAME = "reader"

        fun create(context: Context): OpdsCredentialsManager {
            val backing = context.getSharedPreferences("opds_credentials", Context.MODE_PRIVATE)
            return OpdsCredentialsManager(KeystoreEncryptedPrefs(backing))
        }
    }
}
