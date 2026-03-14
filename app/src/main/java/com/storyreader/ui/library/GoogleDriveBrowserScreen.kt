package com.storyreader.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleDriveBrowserScreen(
    onBack: () -> Unit,
    viewModel: GoogleDriveBrowserViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val authorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onAuthorizationResult(result.data)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GoogleDriveBrowserEvent.LaunchAuthorization -> {
                    authorizationLauncher.launch(event.request)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadInitialFolder()
    }

    BackHandler {
        if (!viewModel.navigateUp()) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.currentFolderName, maxLines = 1) },
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
            items = uiState.items.map { item ->
                RemoteBrowserItemUiModel(
                    id = item.id,
                    title = item.name,
                    isFolder = item.isFolder,
                    trailingMeta = item.size.takeIf { !item.isFolder && it > 0 }?.let(::formatRemoteSize)
                )
            },
            isLoading = uiState.isLoading,
            error = uiState.error,
            emptyMessage = "No EPUB files or folders found",
            downloadingItems = uiState.downloadingItems,
            downloadedCount = uiState.downloadedCount,
            totalToDownload = uiState.totalToDownload,
            modifier = Modifier.fillMaxSize().padding(padding),
            headerContent = {
                uiState.accountLabel?.let { account ->
                    Text(
                        text = account,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            },
            onItemClick = { item ->
                uiState.items.firstOrNull { it.id == item.id }?.takeIf { it.isFolder }?.let(viewModel::navigateTo)
            },
            onItemDownload = { item ->
                uiState.items.firstOrNull { it.id == item.id }?.let(viewModel::downloadItem)
            }
        )
    }
}
