package com.storyreader.util

import android.util.Log
import com.storyreader.BuildConfig

/** Debug-only logging helpers. Calls are completely eliminated in release builds. */
object DebugLog {
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    inline fun v(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.v(tag, message())
    }
}
