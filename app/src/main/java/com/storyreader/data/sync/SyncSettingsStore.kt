package com.storyreader.data.sync

import android.content.Context
import android.content.SharedPreferences

class SyncSettingsStore(
    private val prefs: SharedPreferences
) {

    var isNextcloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_NEXTCLOUD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_NEXTCLOUD_ENABLED, value).apply()

    var isGoogleDriveEnabled: Boolean
        get() = prefs.getBoolean(KEY_GOOGLE_DRIVE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GOOGLE_DRIVE_ENABLED, value).apply()

    companion object {
        private const val KEY_NEXTCLOUD_ENABLED = "nextcloud_enabled"
        private const val KEY_GOOGLE_DRIVE_ENABLED = "google_drive_enabled"

        fun create(context: Context): SyncSettingsStore {
            return SyncSettingsStore(
                context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
            )
        }
    }
}
