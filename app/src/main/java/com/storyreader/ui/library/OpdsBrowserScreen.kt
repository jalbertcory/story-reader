package com.storyreader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsBrowserScreen(
    onBack: () -> Unit,
    viewModel: OpdsBrowserViewModel = viewModel()
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
                title = { Text(uiState.catalogTitle) },
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
        RemoteBrowserContent(
            items = uiState.items.map { entry ->
                RemoteBrowserItemUiModel(
                    id = entry.id,
                    title = entry.title,
                    subtitle = entry.subtitle,
                    isFolder = entry.isNavigation,
                    canDownload = !entry.isNavigation
                )
            },
            isLoading = uiState.isLoading,
            error = uiState.error,
            emptyMessage = "Connect to an OPDS catalog to browse books",
            downloadingItems = uiState.downloadingItems,
            downloadedCount = uiState.downloadedCount,
            totalToDownload = uiState.totalToDownload,
            modifier = Modifier.fillMaxSize().padding(padding),
            headerContent = {
                if (!uiState.hasSavedConfiguration) {
                    Text(
                        text = "Set up your OPDS server in Settings before browsing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            },
            onItemClick = { item ->
                uiState.items.firstOrNull { it.id == item.id }?.takeIf { it.isNavigation }?.let(viewModel::navigateTo)
            },
            onItemDownload = { item ->
                uiState.items.firstOrNull { it.id == item.id }?.takeIf { !it.isNavigation }?.let(viewModel::downloadEntry)
            }
        )
    }
}
