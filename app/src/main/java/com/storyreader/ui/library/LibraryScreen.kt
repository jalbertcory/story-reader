package com.storyreader.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.ui.components.BookCoverThumbnail
import com.storyreader.ui.components.BookProgressRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatLastRead(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diffDays = ((now - timestampMs) / (1000L * 60 * 60 * 24)).toInt()
    return when {
        diffDays == 0 -> "today"
        diffDays == 1 -> "yesterday"
        diffDays < 7 -> "$diffDays days ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onBookClick: (String) -> Unit,
    onSyncSettingsClick: () -> Unit,
    onNextcloudImportClick: () -> Unit,
    onGoogleDriveImportClick: () -> Unit,
    onOpdsImportClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSeriesBrowserClick: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showImportMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var selectedBookForDetail by remember { mutableStateOf<BookEntity?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshCredentials()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importBook(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Library") },
                actions = {
                    // Sort button
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.SortByAlpha, contentDescription = "Sort books")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            LibrarySortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            color = if (option == uiState.sortOption)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        viewModel.setSortOption(option)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                    // Import button — opens dropdown with all import sources
                    Box {
                        IconButton(onClick = { showImportMenu = true }) {
                            Icon(Icons.Default.CloudDownload, contentDescription = "Import book")
                        }
                        DropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false }
                        ) {
                            uiState.importSources.forEachIndexed { index, source ->
                                if (index > 0 && source.requiresCloudDivider) {
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text(source.label) },
                                    leadingIcon = {
                                        Icon(
                                            when (source) {
                                                BookImportSource.DEVICE -> Icons.Default.FolderOpen
                                                else -> Icons.Default.CloudDownload
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showImportMenu = false
                                        when (source) {
                                            BookImportSource.DEVICE -> {
                                                filePickerLauncher.launch(arrayOf("application/epub+zip"))
                                            }
                                            BookImportSource.GOOGLE_DRIVE -> onGoogleDriveImportClick()
                                            BookImportSource.OPDS -> onOpdsImportClick()
                                            BookImportSource.NEXTCLOUD -> onNextcloudImportClick()
                                            BookImportSource.STORY_MANAGER -> onSeriesBrowserClick()
                                        }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = onStatsClick) {
                        Icon(Icons.Default.BarChart, contentDescription = "Reading Stats")
                    }
                    IconButton(onClick = onSyncSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.isStoryManagerBackend) {
                val tabTitles = listOf("Library", "Web Stories")
                PrimaryTabRow(selectedTabIndex = uiState.selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = uiState.selectedTab == index,
                            onClick = { viewModel.selectTab(index) },
                            text = { Text(title) }
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    uiState.selectedTab == 1 && uiState.isStoryManagerBackend -> {
                        WebStoriesTab(
                            groups = uiState.webSeriesGroups,
                            onBookClick = onBookClick
                        )
                    }
                    uiState.libraryGroups.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No books yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Tap the download icon to import an EPUB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LibraryGroupedList(
                        groups = uiState.libraryGroups,
                        lastReadTimes = uiState.lastReadTimes,
                        onBookClick = onBookClick,
                        onBookLongClick = { selectedBookForDetail = it }
                    )
                }
            }

                uiState.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    )
                }
            }
        }
    }

    selectedBookForDetail?.let { book ->
        BookDetailSheet(
            book = book,
            viewModel = viewModel,
            onDismiss = { selectedBookForDetail = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGroupedList(
    groups: List<LibrarySeriesGroup>,
    lastReadTimes: Map<String, Long>,
    onBookClick: (String) -> Unit,
    onBookLongClick: (BookEntity) -> Unit
) {
    val expandedSeries = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(groups, key = { it.seriesName ?: it.books.first().bookId }) { group ->
            if (group.seriesName != null && group.books.size > 1) {
                // Series group — expandable card
                val isExpanded = expandedSeries[group.seriesName] ?: false
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { expandedSeries[group.seriesName] = !isExpanded },
                            onLongClick = {}
                        )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            group.books.firstOrNull()?.coverUri?.let { coverUri ->
                                BookCoverThumbnail(
                                    coverUri = coverUri,
                                    title = group.seriesName,
                                    width = 56.dp,
                                    height = 80.dp
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            ) {
                                Text(
                                    text = group.seriesName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${group.books.size} books",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                group.lastReadTime?.let { lastRead ->
                                    Text(
                                        text = "Last read ${formatLastRead(lastRead)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess
                                    else Icons.Default.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                group.books.forEach { book ->
                                    LibraryBookCard(
                                        book = book,
                                        lastReadAt = lastReadTimes[book.bookId],
                                        onClick = { onBookClick(book.bookId) },
                                        onLongClick = { onBookLongClick(book) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Single book — standard card
                val book = group.books.first()
                LibraryBookCard(
                    book = book,
                    lastReadAt = lastReadTimes[book.bookId],
                    onClick = { onBookClick(book.bookId) },
                    onLongClick = { onBookLongClick(book) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryBookCard(
    book: BookEntity,
    lastReadAt: Long?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            BookCoverThumbnail(
                coverUri = book.coverUri,
                title = book.title,
                width = 56.dp,
                height = 80.dp
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium
                )
                if (book.author.isNotBlank()) {
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (lastReadAt != null) {
                    Text(
                        text = "Last read ${formatLastRead(lastReadAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (book.totalProgression > 0f) {
                    BookProgressRow(
                        progress = book.totalProgression,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}
