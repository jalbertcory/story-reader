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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
