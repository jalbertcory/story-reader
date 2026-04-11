package com.storyreader.reader.tts

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single user-defined regex replacement rule applied to TTS utterance text.
 */
data class TtsRegexRule(
    val pattern: String,
    val replacement: String,
    val enabled: Boolean = true
)

/**
 * Configuration for text filtering applied before utterances reach the TTS engine.
 *
 * [builtInFilters] toggles for common character classes.
 * [customRules] user-defined regex find/replace pairs.
 */
data class TtsTextFilter(
    val stripAngleBrackets: Boolean = false,
    val stripSquareBrackets: Boolean = false,
    val stripCurlyBraces: Boolean = false,
    val stripParentheses: Boolean = false,
    val stripAsterisks: Boolean = false,
    val stripUnderscores: Boolean = false,
    val stripHashes: Boolean = false,
    val stripUrls: Boolean = false,
    val customRules: List<TtsRegexRule> = emptyList()
) {

    /** Build the ordered list of regex replacements from the current config. */
    fun buildReplacements(): List<Pair<Regex, String>> {
        val replacements = mutableListOf<Pair<Regex, String>>()

        if (stripUrls) replacements += Regex("""https?://\S+""") to ""
        if (stripAngleBrackets) replacements += Regex("""[<>]""") to ""
        if (stripSquareBrackets) replacements += Regex("""[\[\]]""") to ""
        if (stripCurlyBraces) replacements += Regex("""[{}]""") to ""
        if (stripParentheses) replacements += Regex("""[()]""") to ""
        if (stripAsterisks) replacements += Regex("""\*+""") to ""
        if (stripUnderscores) replacements += Regex("""_+""") to ""
        if (stripHashes) replacements += Regex("""#+""") to ""

        for (rule in customRules) {
            if (!rule.enabled) continue
            runCatching { Regex(rule.pattern) }
                .onSuccess { regex -> replacements += regex to rule.replacement }
        }

        // Collapse any runs of whitespace left behind by removals.
        if (replacements.isNotEmpty()) {
            replacements += Regex("""\s{2,}""") to " "
        }

        return replacements
    }

    fun hasAnyFilterEnabled(): Boolean =
        stripAngleBrackets || stripSquareBrackets || stripCurlyBraces ||
            stripParentheses || stripAsterisks || stripUnderscores ||
            stripHashes || stripUrls || customRules.any { it.enabled }

    // --- JSON serialization ---

    fun toJson(): JSONObject = JSONObject().apply {
        put("stripAngleBrackets", stripAngleBrackets)
        put("stripSquareBrackets", stripSquareBrackets)
        put("stripCurlyBraces", stripCurlyBraces)
        put("stripParentheses", stripParentheses)
        put("stripAsterisks", stripAsterisks)
        put("stripUnderscores", stripUnderscores)
        put("stripHashes", stripHashes)
        put("stripUrls", stripUrls)
        put("customRules", JSONArray().apply {
            customRules.forEach { rule ->
                put(JSONObject().apply {
                    put("pattern", rule.pattern)
                    put("replacement", rule.replacement)
                    put("enabled", rule.enabled)
                })
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): TtsTextFilter = TtsTextFilter(
            stripAngleBrackets = json.optBoolean("stripAngleBrackets"),
            stripSquareBrackets = json.optBoolean("stripSquareBrackets"),
            stripCurlyBraces = json.optBoolean("stripCurlyBraces"),
            stripParentheses = json.optBoolean("stripParentheses"),
            stripAsterisks = json.optBoolean("stripAsterisks"),
            stripUnderscores = json.optBoolean("stripUnderscores"),
            stripHashes = json.optBoolean("stripHashes"),
            stripUrls = json.optBoolean("stripUrls"),
            customRules = buildList {
                val arr = json.optJSONArray("customRules") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        TtsRegexRule(
                            pattern = obj.optString("pattern", ""),
                            replacement = obj.optString("replacement", ""),
                            enabled = obj.optBoolean("enabled", true)
                        )
                    )
                }
            }
        )

        fun fromJsonString(jsonString: String?): TtsTextFilter {
            if (jsonString.isNullOrBlank()) return TtsTextFilter()
            return runCatching { fromJson(JSONObject(jsonString)) }.getOrDefault(TtsTextFilter())
        }
    }
}
