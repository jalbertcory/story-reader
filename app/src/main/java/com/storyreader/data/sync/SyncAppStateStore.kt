package com.storyreader.data.sync

import android.content.Context
import androidx.core.content.edit
import com.storyreader.StoryReaderApplication
import org.json.JSONObject

internal const val READER_PREFS_NAME = "reader_preferences"
internal const val GOALS_PREFS_NAME = "reading_goals"

internal const val KEY_FONT_SIZE = "font_size"
internal const val KEY_THEME = "theme"
internal const val KEY_FONT_FAMILY = "font_family"
internal const val KEY_IS_NIGHT = "is_night_theme"
internal const val KEY_SCROLL = "scroll_mode"
internal const val KEY_TEXT_ALIGN = "text_align"
internal const val KEY_BRIGHTNESS_LEVEL = "brightness_level"
internal const val KEY_TTS_PREFS = "tts_prefs_json"
internal const val KEY_TTS_ENGINE = "tts_engine"
internal const val KEY_GOAL_HOURS = "goal_hours_per_year"
internal const val KEY_GOAL_WORDS = "goal_words_per_year"

internal const val KEY_SYNC_READER_UPDATED_AT = "sync_reader_updated_at"
internal const val KEY_SYNC_TTS_UPDATED_AT = "sync_tts_updated_at"
internal const val KEY_SYNC_GOALS_UPDATED_AT = "sync_goals_updated_at"

internal const val DEFAULT_GOAL_HOURS = 50
internal const val DEFAULT_GOAL_WORDS = 500_000

class SyncAppStateStore(private val context: Context) {
    private val readerPrefs = context.getSharedPreferences(READER_PREFS_NAME, Context.MODE_PRIVATE)
    private val goalsPrefs = context.getSharedPreferences(GOALS_PREFS_NAME, Context.MODE_PRIVATE)

    fun buildLocalPayload(): SyncAppStatePayload {
        val reader = SyncReaderSettingsPayload(
            updatedAt = readerPrefs.getLong(KEY_SYNC_READER_UPDATED_AT, 0L)
                .takeIf { it > 0L }
                ?: if (hasAnyReaderSettings()) 1L else 0L,
            fontSize = if (readerPrefs.contains(KEY_FONT_SIZE)) {
                readerPrefs.getFloat(KEY_FONT_SIZE, 1.5f).toDouble()
            } else {
                null
            },
            theme = readerPrefs.getString(KEY_THEME, null)?.takeIf { it.isNotBlank() },
            fontFamily = readerPrefs.getString(KEY_FONT_FAMILY, null)?.takeIf { it.isNotBlank() },
            isNightTheme = readerPrefs.getBoolean(KEY_IS_NIGHT, false),
            scrollMode = if (readerPrefs.contains(KEY_SCROLL)) readerPrefs.getBoolean(KEY_SCROLL, false) else null,
            textAlign = readerPrefs.getString(KEY_TEXT_ALIGN, null)?.takeIf { it.isNotBlank() },
            brightnessLevel = if (readerPrefs.contains(KEY_BRIGHTNESS_LEVEL)) {
                readerPrefs.getFloat(KEY_BRIGHTNESS_LEVEL, 1f)
            } else {
                null
            }
        )
        val tts = SyncTtsSettingsPayload(
            updatedAt = readerPrefs.getLong(KEY_SYNC_TTS_UPDATED_AT, 0L)
                .takeIf { it > 0L }
                ?: if (readerPrefs.contains(KEY_TTS_PREFS) || readerPrefs.contains(KEY_TTS_ENGINE)) 1L else 0L,
            preferencesJson = readerPrefs.getString(KEY_TTS_PREFS, null)?.takeIf { it.isNotBlank() },
            enginePackageName = readerPrefs.getString(KEY_TTS_ENGINE, null)?.takeIf { it.isNotBlank() }
        )
        val goals = SyncReadingGoalsPayload(
            updatedAt = goalsPrefs.getLong(KEY_SYNC_GOALS_UPDATED_AT, 0L)
                .takeIf { it > 0L }
                ?: if (goalsPrefs.contains(KEY_GOAL_HOURS) || goalsPrefs.contains(KEY_GOAL_WORDS)) 1L else 0L,
            hoursPerYear = goalsPrefs.getInt(KEY_GOAL_HOURS, DEFAULT_GOAL_HOURS),
            wordsPerYear = goalsPrefs.getInt(KEY_GOAL_WORDS, DEFAULT_GOAL_WORDS)
        )
        return SyncAppStatePayload(reader = reader, tts = tts, goals = goals)
    }

    fun mergeRemoteData(remoteJson: JSONObject) {
        val remote = parseRemotePayload(remoteJson) ?: return
        val local = buildLocalPayload()

        if (remote.reader.updatedAt > local.reader.updatedAt) {
            applyReaderSettings(remote.reader)
        }
        if (remote.tts.updatedAt > local.tts.updatedAt) {
            applyTtsSettings(remote.tts)
        }
        if (remote.goals.updatedAt > local.goals.updatedAt) {
            applyGoals(remote.goals)
        }
    }

    fun parseRemotePayload(remoteJson: JSONObject?): SyncAppStatePayload? {
        remoteJson ?: return null
        val app = remoteJson.optJSONObject("app") ?: return null
        val reader = app.optJSONObject("reader")
        val tts = app.optJSONObject("tts")
        val goals = app.optJSONObject("goals")

        return SyncAppStatePayload(
            reader = SyncReaderSettingsPayload(
                updatedAt = reader?.optLong("updatedAt", 0L) ?: 0L,
                fontSize = reader?.optDoubleOrNull("fontSize"),
                theme = reader?.optStringOrNullLocal("theme"),
                fontFamily = reader?.optStringOrNullLocal("fontFamily"),
                isNightTheme = reader?.optBoolean("isNightTheme", false) == true,
                scrollMode = reader?.optBooleanOrNull("scrollMode"),
                textAlign = reader?.optStringOrNullLocal("textAlign"),
                brightnessLevel = reader?.optFloatOrNullLocal("brightnessLevel")
            ),
            tts = SyncTtsSettingsPayload(
                updatedAt = tts?.optLong("updatedAt", 0L) ?: 0L,
                preferencesJson = tts?.optStringOrNullLocal("preferencesJson"),
                enginePackageName = tts?.optStringOrNullLocal("enginePackageName")
            ),
            goals = SyncReadingGoalsPayload(
                updatedAt = goals?.optLong("updatedAt", 0L) ?: 0L,
                hoursPerYear = goals?.optInt("hoursPerYear", DEFAULT_GOAL_HOURS) ?: DEFAULT_GOAL_HOURS,
                wordsPerYear = goals?.optInt("wordsPerYear", DEFAULT_GOAL_WORDS) ?: DEFAULT_GOAL_WORDS
            )
        )
    }

    private fun hasAnyReaderSettings(): Boolean =
        readerPrefs.contains(KEY_FONT_SIZE) ||
            readerPrefs.contains(KEY_THEME) ||
            readerPrefs.contains(KEY_FONT_FAMILY) ||
            readerPrefs.contains(KEY_IS_NIGHT) ||
            readerPrefs.contains(KEY_SCROLL) ||
            readerPrefs.contains(KEY_TEXT_ALIGN) ||
            readerPrefs.contains(KEY_BRIGHTNESS_LEVEL)

    private fun applyReaderSettings(reader: SyncReaderSettingsPayload) {
        readerPrefs.edit {
            if (reader.fontSize != null) putFloat(KEY_FONT_SIZE, reader.fontSize.toFloat()) else remove(KEY_FONT_SIZE)
            if (reader.theme != null) putString(KEY_THEME, reader.theme) else remove(KEY_THEME)
            if (reader.fontFamily != null) putString(KEY_FONT_FAMILY, reader.fontFamily) else remove(KEY_FONT_FAMILY)
            putBoolean(KEY_IS_NIGHT, reader.isNightTheme)
            if (reader.scrollMode != null) putBoolean(KEY_SCROLL, reader.scrollMode) else remove(KEY_SCROLL)
            if (reader.textAlign != null) putString(KEY_TEXT_ALIGN, reader.textAlign) else remove(KEY_TEXT_ALIGN)
            if (reader.brightnessLevel != null) putFloat(KEY_BRIGHTNESS_LEVEL, reader.brightnessLevel) else remove(KEY_BRIGHTNESS_LEVEL)
            putLong(KEY_SYNC_READER_UPDATED_AT, reader.updatedAt)
        }
        (context.applicationContext as? StoryReaderApplication)?.isDarkReadingTheme?.value =
            reader.theme == "DARK" || reader.isNightTheme
    }

    private fun applyTtsSettings(tts: SyncTtsSettingsPayload) {
        readerPrefs.edit {
            if (tts.preferencesJson != null) putString(KEY_TTS_PREFS, tts.preferencesJson) else remove(KEY_TTS_PREFS)
            if (tts.enginePackageName != null) putString(KEY_TTS_ENGINE, tts.enginePackageName) else remove(KEY_TTS_ENGINE)
            putLong(KEY_SYNC_TTS_UPDATED_AT, tts.updatedAt)
        }
    }

    private fun applyGoals(goals: SyncReadingGoalsPayload) {
        goalsPrefs.edit {
            putInt(KEY_GOAL_HOURS, goals.hoursPerYear)
            putInt(KEY_GOAL_WORDS, goals.wordsPerYear)
            putLong(KEY_SYNC_GOALS_UPDATED_AT, goals.updatedAt)
        }
    }
}

data class SyncAppStatePayload(
    val reader: SyncReaderSettingsPayload,
    val tts: SyncTtsSettingsPayload,
    val goals: SyncReadingGoalsPayload
) {
    fun mergeWith(remote: SyncAppStatePayload?): SyncAppStatePayload {
        if (remote == null) return this
        return SyncAppStatePayload(
            reader = if (remote.reader.updatedAt > reader.updatedAt) remote.reader else reader,
            tts = if (remote.tts.updatedAt > tts.updatedAt) remote.tts else tts,
            goals = if (remote.goals.updatedAt > goals.updatedAt) remote.goals else goals
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("reader", reader.toJson())
        put("tts", tts.toJson())
        put("goals", goals.toJson())
    }
}

data class SyncReaderSettingsPayload(
    val updatedAt: Long,
    val fontSize: Double?,
    val theme: String?,
    val fontFamily: String?,
    val isNightTheme: Boolean,
    val scrollMode: Boolean?,
    val textAlign: String?,
    val brightnessLevel: Float?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("updatedAt", updatedAt)
        if (fontSize != null) put("fontSize", fontSize)
        if (theme != null) put("theme", theme)
        if (fontFamily != null) put("fontFamily", fontFamily)
        put("isNightTheme", isNightTheme)
        if (scrollMode != null) put("scrollMode", scrollMode)
        if (textAlign != null) put("textAlign", textAlign)
        if (brightnessLevel != null) put("brightnessLevel", brightnessLevel.toDouble())
    }
}

data class SyncTtsSettingsPayload(
    val updatedAt: Long,
    val preferencesJson: String?,
    val enginePackageName: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("updatedAt", updatedAt)
        if (preferencesJson != null) put("preferencesJson", preferencesJson)
        if (enginePackageName != null) put("enginePackageName", enginePackageName)
    }
}

data class SyncReadingGoalsPayload(
    val updatedAt: Long,
    val hoursPerYear: Int,
    val wordsPerYear: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("updatedAt", updatedAt)
        put("hoursPerYear", hoursPerYear)
        put("wordsPerYear", wordsPerYear)
    }
}

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

private fun JSONObject.optFloatOrNullLocal(key: String): Float? =
    if (has(key) && !isNull(key)) optDouble(key).toFloat() else null

private fun JSONObject.optStringOrNullLocal(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }
