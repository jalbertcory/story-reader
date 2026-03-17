package com.storyreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import okhttp3.OkHttpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesBrowserScreen(
    onBack: () -> Unit,
    viewModel: SeriesBrowserViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.importSuccessMessage) {
        uiState.importSuccessMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSuccessMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Story Manager") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search...") },
                singleLine = true
            )

            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(32.dp)
                    )
                }
                uiState.error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = uiState.error ?: "",
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = { viewModel.loadAll() },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("Retry")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (uiState.filteredSeries.isNotEmpty()) {
                            item(key = "series_header") {
                                Text(
                                    text = "Series",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            items(uiState.filteredSeries, key = { "series_${it.name}" }) { series ->
                                val isImporting = uiState.importingSeriesName == series.name
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            SeriesCoverImage(
                                                coverUrl = series.coverUrl,
                                                serverBaseUrl = uiState.serverBaseUrl,
                                                authHeader = uiState.authHeader,
                                                seriesName = series.name
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = series.name,
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                                val wordText = when {
                                                    series.totalWords >= 1_000_000 -> "%.1fM words".format(series.totalWords / 1_000_000.0)
                                                    series.totalWords >= 1_000 -> "%.1fK words".format(series.totalWords / 1_000.0)
                                                    else -> "${series.totalWords} words"
                                                }
                                                Text(
                                                    text = "${series.bookCount} books, $wordText",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Button(
                                                onClick = { viewModel.importSeries(series.name) },
                                                enabled = !isImporting && uiState.importingSeriesName == null
                                            ) {
                                                Text(if (isImporting) "Importing..." else "Add All")
                                            }
                                        }
                                        if (isImporting) {
                                            val progress = uiState.importProgress
                                            if (progress != null && progress.second > 0) {
                                                LinearProgressIndicator(
                                                    progress = { progress.first.toFloat() / progress.second },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 8.dp)
                                                )
                                                Text(
                                                    text = "${progress.first}/${progress.second}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                            } else {
                                                LinearProgressIndicator(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.filteredStandaloneBooks.isNotEmpty()) {
                            item(key = "standalone_header") {
                                Text(
                                    text = "Individual Books",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                            items(uiState.filteredStandaloneBooks, key = { "book_${it.id}" }) { book ->
                                val isImporting = uiState.importingBookId == book.id
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        SeriesCoverImage(
                                            coverUrl = book.coverUrl,
                                            serverBaseUrl = uiState.serverBaseUrl,
                                            authHeader = uiState.authHeader,
                                            seriesName = book.title
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = book.title,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            if (book.author.isNotBlank()) {
                                                Text(
                                                    text = book.author,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = { viewModel.importStandaloneBook(book) },
                                            enabled = !isImporting && uiState.importingBookId == null
                                                && uiState.importingSeriesName == null
                                        ) {
                                            Text(if (isImporting) "Adding..." else "Add")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCoverImage(
    coverUrl: String?,
    serverBaseUrl: String,
    authHeader: String?,
    seriesName: String
) {
    if (coverUrl == null) return
    val fullUrl = if (coverUrl.startsWith("http")) coverUrl else "$serverBaseUrl$coverUrl"
    val context = LocalContext.current
    val imageLoader = remember(authHeader) {
        val clientBuilder = OkHttpClient.Builder()
        if (authHeader != null) {
            clientBuilder.addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", authHeader)
                        .build()
                )
            }
        }
        coil.ImageLoader.Builder(context)
            .okHttpClient(clientBuilder.build())
            .build()
    }
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(fullUrl)
            .crossfade(true)
            .build(),
        imageLoader = imageLoader,
        contentDescription = "Cover of $seriesName",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(40.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
    )
}
