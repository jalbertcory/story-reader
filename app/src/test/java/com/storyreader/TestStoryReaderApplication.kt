package com.storyreader

import android.app.Application

/**
 * Minimal Application for Robolectric unit tests. Skips EncryptedSharedPreferences /
 * AndroidKeyStore initialization that is unavailable in the JVM test environment.
 */
class TestStoryReaderApplication : Application()
