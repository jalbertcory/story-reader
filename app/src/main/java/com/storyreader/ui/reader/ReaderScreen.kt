package com.storyreader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidViewBinding
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storyreader.databinding.FragmentReaderContainerBinding
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Publication

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val ttsPlaying by viewModel.ttsPlaying.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) {
        viewModel.openBook(bookId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.finalizeSession() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.publication?.metadata?.title ?: "Loading...",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setTtsPlaying(!ttsPlaying) }) {
                        Icon(
                            if (ttsPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (ttsPlaying) "Stop TTS" else "Start TTS"
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator()
                }
                uiState.error != null -> {
                    Text(
                        text = uiState.error,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                uiState.publication != null -> {
                    EpubReaderContent(
                        publication = uiState.publication,
                        bookId = bookId,
                        viewModel = viewModel
                    )
                }
            }
        }

        if (showSettings) {
            ReaderSettingsSheet(
                preferences = preferences,
                onPreferencesChange = { updated -> viewModel.updatePreferences { updated } },
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
private fun EpubReaderContent(
    publication: Publication,
    bookId: String,
    viewModel: ReaderViewModel
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return

    val navigatorTag = remember(bookId) { "epub_navigator_$bookId" }
    val navigatorFactory = remember(publication) {
        EpubNavigatorFactory(publication)
    }

    AndroidViewBinding(
        factory = FragmentReaderContainerBinding::inflate,
        modifier = Modifier.fillMaxSize()
    ) {
        val fragmentManager = activity.supportFragmentManager
        if (fragmentManager.findFragmentByTag(navigatorTag) == null) {
            val fragment = navigatorFactory.createFragmentFactory(initialLocator = null)
                .instantiate(context.classLoader, EpubNavigatorFragment::class.java.name)
            fragmentManager.beginTransaction()
                .replace(fragmentReaderContainer.id, fragment, navigatorTag)
                .commitNow()
        }
    }
}
