package com.storyreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.storyreader.ui.navigation.StoryReaderNavHost
import com.storyreader.ui.theme.StoryReaderTheme

class MainActivity : ComponentActivity() {
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
