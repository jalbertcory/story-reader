package com.storyreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailSheet(
    book: BookEntity,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sessions by viewModel.getSessionsForBook(book.bookId).collectAsState(initial = emptyList())
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Book header
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (book.coverUri != null) {
                        AsyncImage(
                            model = File(book.coverUri),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(72.dp)
                                .height(104.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .width(72.dp)
                                .height(104.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Book,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleLarge)
                        if (book.author.isNotBlank()) {
                            Text(
                                book.author,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (book.wordCount > 0) {
                            DetailRow("Total words", formatWordCount(book.wordCount.toLong()))
                        }
                        if (book.totalProgression > 0f) {
                            DetailRow("Progress", "${"%.1f".format(book.totalProgression * 100)}%")
                        }
                    }
                }
            }

            // Aggregate stats
            if (sessions.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Reading Stats", style = MaterialTheme.typography.titleMedium)
                }

                item {
                    val manualSessions = sessions.filter { !it.isTts }
                    val ttsSessions = sessions.filter { it.isTts }
                    val totalManualSecs = manualSessions.sumOf { it.durationSeconds }
                    val totalTtsSecs = ttsSessions.sumOf { it.durationSeconds }
                    val totalManualWords = manualSessions.sumOf { it.wordsRead }
                    val totalTtsWords = ttsSessions.sumOf { it.wordsRead }
                    val manualWpm = if (totalManualSecs > 60)
                        (totalManualWords.toFloat() / (totalManualSecs / 60f)).toInt()
                    else 0

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DetailRow("Sessions", "${sessions.size}")
                        DetailRow("Manual reading", formatDuration(totalManualSecs))
                        DetailRow("TTS listening", formatDuration(totalTtsSecs))
                        DetailRow("Words (manual)", formatWordCount(totalManualWords.toLong()))
                        DetailRow("Words (TTS)", formatWordCount(totalTtsWords.toLong()))
                        if (manualWpm > 0) {
                            DetailRow("Manual WPM", "$manualWpm")
                        }
                    }
                }
            }

            // Session history
            if (sessions.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Session History", style = MaterialTheme.typography.titleMedium)
                }

                items(sessions.take(20)) { session ->
                    SessionRow(session, dateFormat)
                }

                if (sessions.size > 20) {
                    item {
                        Text(
                            "... and ${sessions.size - 20} more sessions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Remove button
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { showRemoveConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Remove from Library")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove Book?") },
            text = {
                Text("\"${book.title}\" will be removed from your library. Reading stats will be preserved.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.hideBook(book.bookId)
                    showRemoveConfirm = false
                    onDismiss()
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SessionRow(session: ReadingSessionEntity, dateFormat: SimpleDateFormat) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    dateFormat.format(Date(session.startTime)),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (session.isTts) "TTS" else "Manual",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatDuration(session.durationSeconds),
                    style = MaterialTheme.typography.bodySmall
                )
                if (session.wordsRead > 0) {
                    Text(
                        "${formatWordCount(session.wordsRead.toLong())} words",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatWordCount(words: Long): String {
    return when {
        words >= 1_000_000 -> "%.1fM".format(words / 1_000_000f)
        words >= 1_000 -> "%.1fK".format(words / 1_000f)
        else -> "$words"
    }
}
