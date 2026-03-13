package com.storyreader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.storyreader.ui.navigation.StoryReaderNavHost
import com.storyreader.ui.theme.StoryReaderTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StoryReaderTheme {
                StoryReaderNavHost()
            }
        }
    }
}
