package com.storyreader.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SyncSettingsStore(
    private val prefs: SharedPreferences
) {

    var isNextcloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_NEXTCLOUD_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_NEXTCLOUD_ENABLED, value) }

    var isGoogleDriveEnabled: Boolean
        get() = prefs.getBoolean(KEY_GOOGLE_DRIVE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_GOOGLE_DRIVE_ENABLED, value) }

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
