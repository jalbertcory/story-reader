package com.storyreader.util

import android.util.Log

/**
 * Like [getOrDefault] but logs the failure before returning the default.
 */
inline fun <T> Result<T>.getOrDefaultLogging(
    tag: String,
    message: String,
    defaultValue: T
): T = getOrElse { e ->
    Log.w(tag, "$message: ${e.message}", e)
    defaultValue
}

/**
 * Like [getOrNull] but logs the failure before returning null.
 */
inline fun <T> Result<T>.getOrNullLogging(
    tag: String,
    message: String
): T? = getOrElse { e ->
    Log.w(tag, "$message: ${e.message}", e)
    null
}
