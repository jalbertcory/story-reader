package com.storyreader.ui.stats

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.storyreader.data.db.dao.MonthlyReadingStat
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
            // Year selector tabs
            if (uiState.availableYears.size > 1) {
                item {
                    YearSelector(
                        years = uiState.availableYears,
                        selectedYear = uiState.selectedYear,
                        onYearSelect = viewModel::selectYear,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            uiState.globalStats?.let { global ->
                item {
                    GlobalStatsCard(
                        global = global,
                        monthlyStats = uiState.monthlyStats,
                        onGoalHoursChange = viewModel::setGoalHours,
                        onGoalWordsChange = viewModel::setGoalWords
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YearSelector(
    years: List<Int>,
    selectedYear: Int,
    onYearSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = years.indexOf(selectedYear).coerceAtLeast(0)
    SecondaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier
    ) {
        years.forEach { year ->
            Tab(
                selected = year == selectedYear,
                onClick = { onYearSelect(year) },
                text = { Text(year.toString()) }
            )
        }
    }
}

@Composable
private fun MonthlyBarChart(
    monthlyStats: List<MonthlyReadingStat>,
    modifier: Modifier = Modifier
) {
    val monthlySeconds = (1..12).map { month ->
        monthlyStats.find { it.month == month }?.totalSeconds ?: 0L
    }
    val maxVal = monthlySeconds.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val monthLabels = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
    val barColor = MaterialTheme.colorScheme.primary
    val emptyBarColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        ) {
            val slotWidth = size.width / 12f
            val barWidth = slotWidth * 0.6f
            val gap = slotWidth * 0.2f

            monthlySeconds.forEachIndexed { i, value ->
                val barHeight = if (value > 0)
                    (value.toFloat() / maxVal * size.height).coerceAtLeast(3f)
                else 0f
                val x = i * slotWidth + gap
                // Draw empty slot background
                drawRect(
                    color = emptyBarColor,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, size.height)
                )
                // Draw filled portion
                if (barHeight > 0f) {
                    drawRect(
                        color = barColor,
                        topLeft = Offset(x, size.height - barHeight),
                        size = Size(barWidth, barHeight)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            monthLabels.forEachIndexed { i, label ->
                val hasData = (monthlySeconds.getOrNull(i) ?: 0L) > 0L
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasData) labelColor else labelColor.copy(alpha = 0.4f),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun GlobalStatsCard(
    global: GlobalStats,
    monthlyStats: List<MonthlyReadingStat>,
    onGoalHoursChange: (Int) -> Unit,
    onGoalWordsChange: (Int) -> Unit
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
                Text(
                    "${global.selectedYear}${if (global.isCurrentYear) " — Year to Date" else ""}",
                    style = MaterialTheme.typography.titleMedium
                )
                if (global.isCurrentYear) {
                    TextButton(onClick = { showGoalDialog = true }) {
                        Text("Edit Goals")
                    }
                }
            }

            // Monthly bar chart
            if (monthlyStats.isNotEmpty() || !global.isCurrentYear) {
                MonthlyBarChart(
                    monthlyStats = monthlyStats,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (global.isCurrentYear) {
                val ytdHours = global.selectedYearTotalSeconds / 3600f
                val hoursProgress = (ytdHours / global.goalHoursPerYear).coerceIn(0f, 1f)
                GoalProgressRow(
                    label = "Hours Read",
                    current = "%.1f h".format(ytdHours),
                    goal = "${global.goalHoursPerYear} h",
                    progress = hoursProgress
                )
                val wordsProgress = (global.selectedYearTotalWords.toFloat() / global.goalWordsPerYear).coerceIn(0f, 1f)
                GoalProgressRow(
                    label = "Words Read",
                    current = formatWords(global.selectedYearTotalWords),
                    goal = formatWords(global.goalWordsPerYear.toLong()),
                    progress = wordsProgress
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatChip(
                        value = formatHours(global.selectedYearTotalSeconds),
                        label = "Hours Read"
                    )
                    StatChip(
                        value = formatWords(global.selectedYearTotalWords),
                        label = "Words Read"
                    )
                }
            }

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
                    value = formatWords(global.allTimeTotalWords),
                    label = "Words Read"
                )
                if (global.allTimeManualWpm > 0) {
                    StatChip(
                        value = "${global.allTimeManualWpm}",
                        label = "Manual WPM"
                    )
                }
            }
        }
    }

    if (showGoalDialog) {
        GoalEditDialog(
            currentHours = global.goalHoursPerYear,
            currentWords = global.goalWordsPerYear,
            onConfirm = { hours, words ->
                onGoalHoursChange(hours)
                onGoalWordsChange(words)
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
            modifier = Modifier.fillMaxWidth(),
            drawStopIndicator = {}
        )
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun BookStatCard(item: BookStatItem) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    // WPM based on manual reading only (not TTS)
    val manualWpm = if (item.manualDurationSeconds > 60)
        (item.manualWordsRead.toFloat() / (item.manualDurationSeconds / 60f)).toInt()
    else 0

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp)) {
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
                    LabelValue("Reading", formatHours(item.manualDurationSeconds.toLong()))
                    if (item.ttsDurationSeconds > 0) {
                        LabelValue("TTS", formatHours(item.ttsDurationSeconds.toLong()))
                    }
                    LabelValue("Words", formatWords(item.totalWordsRead.toLong()))
                }
                if (manualWpm > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LabelValue("Manual WPM", "$manualWpm")
                    }
                }
                if (item.book.totalProgression > 0f) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { item.book.totalProgression },
                            modifier = Modifier.weight(1f),
                            drawStopIndicator = {}
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
    currentWords: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var hoursText by remember { mutableStateOf(currentHours.toString()) }
    var wordsText by remember { mutableStateOf(currentWords.toString()) }

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
                    value = wordsText,
                    onValueChange = { wordsText = it.filter { c -> c.isDigit() } },
                    label = { Text("Target words per year") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hours = hoursText.toIntOrNull()?.coerceIn(1, 10000) ?: currentHours
                val words = wordsText.toIntOrNull()?.coerceIn(1000, 10_000_000) ?: currentWords
                onConfirm(hours, words)
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

private fun formatWords(words: Long): String {
    return when {
        words >= 1_000_000 -> "%.1fM".format(words / 1_000_000f)
        words >= 1_000 -> "%.1fK".format(words / 1_000f)
        else -> "$words"
    }
}
