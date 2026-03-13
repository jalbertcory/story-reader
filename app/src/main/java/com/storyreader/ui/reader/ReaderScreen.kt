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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatListBulleted
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val currentLocator by viewModel.currentLocator.collectAsState()
    val showBars by viewModel.showBars.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) {
        viewModel.openBook(bookId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.finalizeSession() }
    }

    // Show/hide system bars to match app bar state
    val view = LocalView.current
    val window = (view.context as? FragmentActivity)?.window
    SideEffect {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (showBars) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = showBars,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
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
                        if (uiState.publication != null) {
                            IconButton(onClick = { showToc = true }) {
                                Icon(@Suppress("DEPRECATION") Icons.Default.FormatListBulleted, contentDescription = "Table of Contents")
                            }
                        }
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
        }
    ) { padding ->
        val topPadding = if (showBars) padding.calculateTopPadding() else 0.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding, bottom = padding.calculateBottomPadding())
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator()
                    }
                    uiState.error != null -> {
                        Text(
                            text = uiState.error ?: "",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    uiState.publication != null -> {
                        EpubReaderContent(
                            publication = uiState.publication!!,
                            bookId = bookId,
                            initialLocatorJson = uiState.initialLocatorJson,
                            viewModel = viewModel
                        )
                    }
                }
            }

            if (uiState.publication != null) {
                ReaderStatusBar(
                    locator = currentLocator,
                    toc = uiState.publication!!.tableOfContents
                )
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
    // Flatten TOC (two levels) and find the best match by href prefix
    val allLinks = toc.flatMap { link -> listOf(link) + (link.children ?: emptyList()) }
    return allLinks
        .filter { link -> href.startsWith(link.href.toString().substringBefore("#")) }
        .maxByOrNull { it.href.toString().length }
        ?.title
        ?: locator.title
        ?: ""
}

@Composable
private fun ReaderStatusBar(locator: Locator?, toc: List<Link>) {
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
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = progressText,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = chapterTitle,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(3f)
        )
        Text(
            text = "$currentTime  $batteryPct%",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    viewModel: ReaderViewModel
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
        update = { view ->
            val fm = activity.supportFragmentManager
            if (fm.findFragmentByTag(navigatorTag) == null) {
                fm.fragmentFactory = fragmentFactory
                fm.beginTransaction()
                    .replace(containerId, EpubNavigatorFragment::class.java, null, navigatorTag)
                    .commitNow()
            }
            val nav = fm.findFragmentByTag(navigatorTag) as? EpubNavigatorFragment
            if (nav != null) {
                viewModel.onNavigatorReady(nav)
            }
        },
        modifier = Modifier.fillMaxSize()
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
