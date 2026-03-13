package com.storyreader.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading Stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Global stats card
            uiState.globalStats?.let { global ->
                item {
                    GlobalStatsCard(
                        global = global,
                        onGoalHoursChange = viewModel::setGoalHours,
                        onGoalBooksChange = viewModel::setGoalBooks
                    )
                }
            }

            if (uiState.bookStats.isEmpty()) {
                item {
                    Text(
                        text = "No reading history yet. Open a book to start tracking!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                item {
                    Text(
                        text = "Books",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(uiState.bookStats) { item ->
                    BookStatCard(item)
                }
            }
        }
    }
}

@Composable
private fun GlobalStatsCard(
    global: GlobalStats,
    onGoalHoursChange: (Int) -> Unit,
    onGoalBooksChange: (Int) -> Unit
) {
    var showGoalDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Reading Goals", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showGoalDialog = true }) {
                    Text("Edit Goals")
                }
            }

            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            Text(
                "$currentYear — Year to Date",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            // Hours goal
            val ytdHours = global.ytdTotalSeconds / 3600f
            val hoursProgress = (ytdHours / global.goalHoursPerYear).coerceIn(0f, 1f)
            GoalProgressRow(
                label = "Hours Read",
                current = "%.1f h".format(ytdHours),
                goal = "${global.goalHoursPerYear} h",
                progress = hoursProgress
            )

            // Books goal
            val booksProgress = (global.ytdBooksStarted.toFloat() / global.goalBooksPerYear).coerceIn(0f, 1f)
            GoalProgressRow(
                label = "Books Started",
                current = "${global.ytdBooksStarted}",
                goal = "${global.goalBooksPerYear}",
                progress = booksProgress
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                "All Time",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatChip(
                    value = formatHours(global.allTimeTotalSeconds),
                    label = "Hours Read"
                )
                StatChip(
                    value = "${global.allTimeBooksStarted}",
                    label = "Books"
                )
            }
        }
    }

    if (showGoalDialog) {
        GoalEditDialog(
            currentHours = global.goalHoursPerYear,
            currentBooks = global.goalBooksPerYear,
            onConfirm = { hours, books ->
                onGoalHoursChange(hours)
                onGoalBooksChange(books)
                showGoalDialog = false
            },
            onDismiss = { showGoalDialog = false }
        )
    }
}

@Composable
private fun GoalProgressRow(label: String, current: String, goal: String, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                "$current / $goal",
                style = MaterialTheme.typography.bodySmall,
                color = if (progress >= 1f)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

@Composable
private fun BookStatCard(item: BookStatItem) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp)) {
            // Cover
            if (item.book.coverUri != null) {
                AsyncImage(
                    model = File(item.book.coverUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(48.dp)
                        .height(68.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(68.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Book,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                if (item.book.author.isNotBlank()) {
                    Text(
                        item.book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LabelValue("Started", dateFormat.format(Date(item.firstReadMs)))
                    LabelValue("Last read", dateFormat.format(Date(item.lastReadMs)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LabelValue("Time read", formatHours(item.totalReadingSeconds.toLong()))
                    LabelValue("Sessions", "${item.sessionCount}")
                }
                if (item.book.totalProgression > 0f) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { item.book.totalProgression },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${"%.1f".format(item.book.totalProgression * 100)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GoalEditDialog(
    currentHours: Int,
    currentBooks: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var hoursText by remember { mutableStateOf(currentHours.toString()) }
    var booksText by remember { mutableStateOf(currentBooks.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yearly Reading Goals") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it.filter { c -> c.isDigit() } },
                    label = { Text("Target hours per year") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = booksText,
                    onValueChange = { booksText = it.filter { c -> c.isDigit() } },
                    label = { Text("Target books per year") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hours = hoursText.toIntOrNull()?.coerceIn(1, 10000) ?: currentHours
                val books = booksText.toIntOrNull()?.coerceIn(1, 1000) ?: currentBooks
                onConfirm(hours, books)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatHours(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
