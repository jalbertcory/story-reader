package com.storyreader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import com.storyreader.ui.navigation.StoryReaderNavHost
import com.storyreader.ui.theme.StoryReaderTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(_savedInstanceState: Bundle?) {
        // Pass null to skip all state restoration. EpubNavigatorFragment has no no-arg
        // constructor so the FragmentManager cannot re-instantiate it after process death.
        // The reader re-opens automatically via StoryReaderNavHost's most-recent-book logic.
        super.onCreate(null)
        enableEdgeToEdge()
        val app = application as StoryReaderApplication
        setContent {
            val isDark by app.isDarkReadingTheme.collectAsState()
            StoryReaderTheme(forceDark = if (isDark) true else null) {
                StoryReaderNavHost()
            }
        }
    }
}
