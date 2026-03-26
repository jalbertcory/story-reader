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

    var lastSyncTimestampMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNC_TIMESTAMP, value) }

    var lastSyncError: String?
        get() = prefs.getString(KEY_LAST_SYNC_ERROR, null)
        set(value) = prefs.edit {
            if (value != null) putString(KEY_LAST_SYNC_ERROR, value)
            else remove(KEY_LAST_SYNC_ERROR)
        }

    companion object {
        private const val KEY_NEXTCLOUD_ENABLED = "nextcloud_enabled"
        private const val KEY_GOOGLE_DRIVE_ENABLED = "google_drive_enabled"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        private const val KEY_LAST_SYNC_ERROR = "last_sync_error"

        fun create(context: Context): SyncSettingsStore {
            return SyncSettingsStore(
                context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
            )
        }
    }
}
