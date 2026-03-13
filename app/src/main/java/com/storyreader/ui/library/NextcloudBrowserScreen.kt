package com.storyreader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storyreader.data.sync.NextcloudItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudBrowserScreen(
    onBack: () -> Unit,
    viewModel: NextcloudBrowserViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    BackHandler {
        if (!viewModel.navigateUp()) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nextcloud Files", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!viewModel.navigateUp()) onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            if (uiState.totalToDownload > 0) {
                LinearProgressIndicator(
                    progress = {
                        uiState.downloadedCount.toFloat() / uiState.totalToDownload.coerceAtLeast(1)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {}
                )
                Text(
                    text = "Downloaded ${uiState.downloadedCount} / ${uiState.totalToDownload}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    uiState.items.isEmpty() && uiState.error == null -> {
                        Text(
                            text = "No EPUB files or folders found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.items, key = { it.url }) { item ->
                                NextcloudItemRow(
                                    item = item,
                                    isDownloading = item.url in uiState.downloadingItems,
                                    onClick = {
                                        if (item.isFolder) {
                                            viewModel.navigateTo(item.url)
                                        }
                                    },
                                    onDownload = { viewModel.downloadItem(item) }
                                )
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
}

@Composable
private fun NextcloudItemRow(
    item: NextcloudItem,
    isDownloading: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (item.isFolder) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (item.isFolder)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.isFolder && item.size > 0) {
                    Text(
                        text = formatSize(item.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = if (item.isFolder) "Download all EPUBs" else "Download"
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
