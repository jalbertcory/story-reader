package com.storyreader.ui.reader

import android.content.Context
import android.os.BatteryManager
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Detect night theme from preferences
@OptIn(ExperimentalReadiumApi::class)
private fun EpubPreferences?.isNightTheme() =
    this != null && theme == null && backgroundColor?.int == 0xFF000000.toInt()

// Status-bar colors that match each reading theme so it blends with the page
private data class StatusBarStyle(val bg: Color, val text: Color)

@OptIn(ExperimentalReadiumApi::class)
private fun statusBarStyleFor(preferences: EpubPreferences?): StatusBarStyle = when {
    preferences.isNightTheme() -> StatusBarStyle(
        bg = Color(0xCC000000),
        text = Color(0xFFFF7722)
    )
    preferences?.theme == Theme.DARK -> StatusBarStyle(
        bg = Color(0xCC1A1A2E),
        text = Color(0xCCE0E0E0)
    )
    preferences?.theme == Theme.SEPIA -> StatusBarStyle(
        bg = Color(0xCCF5EBD7),
        text = Color(0xFF5C4033)
    )
    else -> StatusBarStyle(
        bg = Color(0xCCFFFFFF),
        text = Color(0xFF1C1B1F)
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalReadiumApi::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val ttsState by viewModel.ttsState.collectAsState()
    val currentLocator by viewModel.currentLocator.collectAsState()
    val showBars by viewModel.showBars.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) { viewModel.openBook(bookId) }
    DisposableEffect(Unit) { onDispose { viewModel.finalizeSession() } }

    // Always hide system bars in the reader. The reading window must never change size
    // due to system bar show/hide, so we keep them permanently hidden.
    // Users can swipe from edge to reveal system bars transiently if needed.
    val view = LocalView.current
    val window = (view.context as? FragmentActivity)?.window
    SideEffect {
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Apply dark theme to app chrome when reading in dark/night mode
    val isDarkReadingTheme = preferences.theme == Theme.DARK || preferences.isNightTheme()
    val readerColorScheme = if (isDarkReadingTheme) darkColorScheme() else MaterialTheme.colorScheme
    val statusBarStyle = statusBarStyleFor(preferences)

    MaterialTheme(colorScheme = readerColorScheme) {
        // IMPORTANT: The reading WebView must never change size — any resize causes Readium to
        // reflow the content and recalculate page positions, which jumps the reading location.
        // All UI chrome (top bar, TTS controls, status bar) is OVERLAID on top of the reading
        // content using a Box layout so the WebView always occupies the full screen.
        // This also means system bars are permanently hidden in this screen to avoid
        // any inset-driven layout changes. See ReaderViewModel.onNavigatorReady for TTS tap handling.
        Box(modifier = Modifier.fillMaxSize()) {

            // ── Reading content (always full-screen) ─────────────────────────────
            when {
                uiState.isLoading -> {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.error != null -> {
                    Text(
                        text = uiState.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.publication != null -> {
                    EpubReaderContent(
                        publication = uiState.publication!!,
                        bookId = bookId,
                        initialLocatorJson = uiState.initialLocatorJson,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ── Top bar overlay (hidden in fullscreen or during TTS) ─────────────
            // Hidden when bars are toggled off OR when TTS is active (to maintain reading area parity)
            AnimatedVisibility(
                visible = showBars && ttsState == TtsPlaybackState.STOPPED,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = {
                        Text(
                            text = uiState.publication?.metadata?.title ?: "",
                            maxLines = 1
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (uiState.publication != null) {
                            IconButton(onClick = { showToc = true }) {
                                Icon(
                                    @Suppress("DEPRECATION") Icons.Default.FormatListBulleted,
                                    contentDescription = "Table of Contents"
                                )
                            }
                        }
                        if (ttsState == TtsPlaybackState.STOPPED) {
                            IconButton(onClick = { viewModel.startTts() }) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Start TTS",
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = readerColorScheme.surface.copy(alpha = 0.92f)
                    )
                )
            }

            // ── Bottom overlay (TTS controls + status bar) ───────────────────────
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                // TTS controls bar — visible when TTS is active (replaces top bar to keep same reading area)
                AnimatedVisibility(
                    visible = ttsState != TtsPlaybackState.STOPPED,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    TtsControlsBar(
                        isPlaying = ttsState == TtsPlaybackState.PLAYING,
                        onPlayPause = viewModel::ttsPlayPause,
                        onStop = viewModel::stopTts,
                        onPrevious = viewModel::ttsSkipPrevious,
                        onNext = viewModel::ttsSkipNext,
                        style = statusBarStyle
                    )
                }

                // Status bar — always visible, themed to match reading content
                if (uiState.publication != null) {
                    ReaderStatusBar(
                        locator = currentLocator,
                        toc = uiState.publication!!.tableOfContents,
                        style = statusBarStyle
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

        if (showToc) {
            TocSheet(
                toc = uiState.publication?.tableOfContents.orEmpty(),
                onNavigate = { link ->
                    viewModel.navigateToLink(link)
                    showToc = false
                },
                onDismiss = { showToc = false }
            )
        }
    }
}

private fun chapterTitleFromToc(locator: Locator?, toc: List<Link>): String {
    if (locator == null) return ""
    val href = locator.href.toString()
    val allLinks = toc.flatMap { link -> listOf(link) + (link.children ?: emptyList()) }
    return allLinks
        .filter { link -> href.startsWith(link.href.toString().substringBefore("#")) }
        .maxByOrNull { it.href.toString().length }
        ?.title
        ?: locator.title
        ?: ""
}

@Composable
private fun TtsControlsBar(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    style: StatusBarStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(style.bg)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous page", tint = style.text)
        }
        IconButton(onClick = onPlayPause) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = style.text,
                modifier = Modifier.size(32.dp)
            )
        }
        IconButton(onClick = onStop) {
            Icon(Icons.Default.Stop, contentDescription = "Stop TTS", tint = style.text, modifier = Modifier.size(28.dp))
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next page", tint = style.text)
        }
    }
}

@Composable
private fun ReaderStatusBar(locator: Locator?, toc: List<Link>, style: StatusBarStyle) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    var currentTime by remember { mutableStateOf(timeFormat.format(Date())) }
    var batteryPct by remember { mutableIntStateOf(getBatteryLevel(context)) }

    LaunchedEffect(Unit) {
        while (isActive) {
            currentTime = timeFormat.format(Date())
            batteryPct = getBatteryLevel(context)
            delay(30_000)
        }
    }

    val progressText = locator?.locations?.totalProgression?.let {
        "${"%.1f".format(it * 100)}%"
    } ?: ""
    val chapterTitle = chapterTitleFromToc(locator, toc)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(style.bg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(progressText, fontSize = 10.sp, color = style.text, modifier = Modifier.weight(1f))
        Text(
            chapterTitle,
            fontSize = 10.sp,
            color = style.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(3f)
        )
        Text(
            "$currentTime  $batteryPct%",
            fontSize = 10.sp,
            color = style.text,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.5f)
        )
    }
}

private fun getBatteryLevel(context: Context): Int {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}

@Composable
private fun EpubReaderContent(
    publication: Publication,
    bookId: String,
    initialLocatorJson: String?,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return

    val navigatorTag = remember(bookId) { "epub_navigator_$bookId" }
    val containerId = remember { View.generateViewId() }
    val initialLocator = remember(initialLocatorJson) {
        initialLocatorJson?.let { Locator.fromJSON(org.json.JSONObject(it)) }
    }
    val fragmentFactory = remember(publication, initialLocator) {
        EpubNavigatorFactory(publication).createFragmentFactory(initialLocator = initialLocator)
    }

    AndroidView(
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { _ ->
            val fm = activity.supportFragmentManager
            if (fm.findFragmentByTag(navigatorTag) == null) {
                fm.fragmentFactory = fragmentFactory
                fm.beginTransaction()
                    .replace(containerId, EpubNavigatorFragment::class.java, null, navigatorTag)
                    .commitNow()
            }
            val nav = fm.findFragmentByTag(navigatorTag) as? EpubNavigatorFragment
            if (nav != null) viewModel.onNavigatorReady(nav)
        },
        modifier = modifier
    )

    DisposableEffect(navigatorTag) {
        onDispose {
            val fm = activity.supportFragmentManager
            fm.findFragmentByTag(navigatorTag)?.let { fragment ->
                fm.beginTransaction().remove(fragment).commitAllowingStateLoss()
            }
        }
    }
}
