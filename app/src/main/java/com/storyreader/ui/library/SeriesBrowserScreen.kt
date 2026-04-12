package com.storyreader.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.storyreader.data.catalog.ServerBook
import com.storyreader.ui.components.StoryReaderLinearProgressIndicator
import okhttp3.OkHttpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesBrowserScreen(
    onBack: () -> Unit,
    viewModel: SeriesBrowserViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coverImageLoader = remember(uiState.authHeader) {
        val clientBuilder = OkHttpClient.Builder()
        val authHeader = uiState.authHeader
        if (authHeader != null) {
            clientBuilder.addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", authHeader)
                        .build()
                )
            }
        }
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { clientBuilder.build() }))
            }
            .build()
    }

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
            var showGenreFilter by rememberSaveable { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search series and books...") },
                    singleLine = true
                )
                if (uiState.allGenres.isNotEmpty()) {
                    IconButton(onClick = { showGenreFilter = !showGenreFilter }) {
                        Icon(
                            imageVector = Icons.Filled.FilterList,
                            contentDescription = "Filter by genre",
                            tint = if (uiState.genreFilters.isNotEmpty() || showGenreFilter) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            @OptIn(ExperimentalLayoutApi::class)
            AnimatedVisibility(visible = showGenreFilter && uiState.allGenres.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    uiState.allGenres.forEach { genre ->
                        FilterChip(
                            selected = uiState.genreFilters.any { it.equals(genre, ignoreCase = true) },
                            onClick = { viewModel.toggleGenreFilter(genre) },
                            label = { Text(genre, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
            }

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
                    SeriesBrowserContent(uiState, viewModel, coverImageLoader)
                }
            }
        }
    }
}

@Composable
private fun SeriesBrowserContent(
    uiState: SeriesBrowserUiState,
    viewModel: SeriesBrowserViewModel,
    coverImageLoader: ImageLoader
) {
    // Combine name-matched series and book-matched series
    val bookMatchedSeriesNames = uiState.seriesMatchedByBook.keys
    val allVisibleSeries = uiState.filteredSeries +
        uiState.allSeries.filter { it.name in bookMatchedSeriesNames }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (allVisibleSeries.isNotEmpty()) {
            item(key = "series_header") {
                Text(
                    text = "Series",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(allVisibleSeries, key = { "series_${it.name}" }) { series ->
                SeriesCard(series, uiState, viewModel, coverImageLoader)
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
                BookCard(
                    book = book,
                    isImporting = uiState.importingBookId == book.id,
                    importEnabled = uiState.importingBookId == null && uiState.importingSeriesName == null,
                    serverBaseUrl = uiState.serverBaseUrl,
                    coverImageLoader = coverImageLoader,
                    onImport = { viewModel.importBook(book) }
                )
            }
        }
    }
}

@Composable
private fun SeriesCard(
    series: com.storyreader.data.catalog.SeriesSummary,
    uiState: SeriesBrowserUiState,
    viewModel: SeriesBrowserViewModel,
    coverImageLoader: ImageLoader
) {
    val isExpanded = series.name in uiState.expandedSeries
    val isImporting = uiState.importingSeriesName == series.name
    val isLoadingBooks = series.name in uiState.loadingSeriesBooks
    val books = uiState.seriesBooks[series.name]

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleSeriesExpanded(series.name) },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isExpanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(24.dp)
                )
                AuthenticatedCoverImage(
                    coverUrl = series.coverUrl,
                    serverBaseUrl = uiState.serverBaseUrl,
                    imageLoader = coverImageLoader,
                    contentDescription = "Cover of ${series.name}"
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = series.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    val wordText = formatWordCount(series.totalWords)
                    Text(
                        text = "${series.bookCount} books, $wordText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GenreTagRow(series.genreTags)
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
                    StoryReaderLinearProgressIndicator(
                        progress = progress.first.toFloat() / progress.second,
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
                    StoryReaderLinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }

            // Expanded book list
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isLoadingBooks) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.CenterHorizontally),
                            strokeWidth = 2.dp
                        )
                    } else if (books != null) {
                        books.forEach { book ->
                            BookCard(
                                book = book,
                                isImporting = uiState.importingBookId == book.id,
                                importEnabled = uiState.importingBookId == null
                                    && uiState.importingSeriesName == null,
                                serverBaseUrl = uiState.serverBaseUrl,
                                coverImageLoader = coverImageLoader,
                                onImport = { viewModel.importBook(book) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: ServerBook,
    isImporting: Boolean,
    importEnabled: Boolean,
    serverBaseUrl: String,
    coverImageLoader: ImageLoader,
    onImport: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AuthenticatedCoverImage(
                coverUrl = book.coverUrl,
                serverBaseUrl = serverBaseUrl,
                imageLoader = coverImageLoader,
                contentDescription = "Cover of ${book.title}"
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
                book.currentWordCount?.let { words ->
                    Text(
                        text = formatWordCount(words),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(
                onClick = onImport,
                enabled = !isImporting && importEnabled
            ) {
                Text(if (isImporting) "Adding..." else "Add")
            }
        }
    }
}

@Composable
private fun AuthenticatedCoverImage(
    coverUrl: String?,
    serverBaseUrl: String,
    imageLoader: ImageLoader,
    contentDescription: String
) {
    if (coverUrl == null) return
    val path = if (coverUrl.startsWith("http")) {
        // Extract path from absolute URL — always resolve against the configured server base
        // to avoid HTTP/HTTPS mismatches with production servers.
        android.net.Uri.parse(coverUrl).path ?: coverUrl
    } else {
        coverUrl
    }
    val fullUrl = "$serverBaseUrl$path"
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(fullUrl)
            .crossfade(true)
            .build(),
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(40.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreTagRow(tags: List<String>) {
    if (tags.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        tags.forEach { tag ->
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(vertical = 1.dp)
            )
            if (tag != tags.last()) {
                Text(
                    text = "\u00B7",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

private fun formatWordCount(words: Int): String = when {
    words >= 1_000_000 -> "%.1fM words".format(words / 1_000_000.0)
    words >= 1_000 -> "%.1fK words".format(words / 1_000.0)
    else -> "$words words"
}
