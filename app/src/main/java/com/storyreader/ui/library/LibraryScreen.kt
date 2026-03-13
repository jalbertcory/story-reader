package com.storyreader.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.io.File
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (String) -> Unit,
    onSyncSettingsClick: () -> Unit,
    onNextcloudImportClick: () -> Unit,
    onStatsClick: () -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
                    if (uiState.hasNextcloudCredentials) {
                        IconButton(onClick = onNextcloudImportClick) {
                            Icon(Icons.Default.CloudDownload, contentDescription = "Import from Nextcloud")
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
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("application/epub+zip")) }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Import from device")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.books.isEmpty() -> {
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
                            text = if (uiState.hasNextcloudCredentials)
                                "Tap + to import from device, or the cloud icon for Nextcloud"
                            else
                                "Tap + to import an EPUB from your device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.books, key = { it.bookId }) { book ->
                            val lastReadAt = uiState.lastReadTimes[book.bookId]
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onBookClick(book.bookId) }
                            ) {
                                Row(modifier = Modifier.padding(12.dp)) {
                                    // Cover thumbnail
                                    if (book.coverUri != null) {
                                        AsyncImage(
                                            model = File(book.coverUri),
                                            contentDescription = "Cover of ${book.title}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .width(56.dp)
                                                .height(80.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .width(56.dp)
                                                .height(80.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .padding(4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Book,
                                                contentDescription = null,
                                                modifier = Modifier.size(36.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
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
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.padding(top = 6.dp)
                                            ) {
                                                LinearProgressIndicator(
                                                    progress = { book.totalProgression },
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "${"%.1f".format(book.totalProgression * 100)}%",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
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
