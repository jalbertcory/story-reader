package com.storyreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.ui.components.BookCoverThumbnail

private fun formatRelativeTime(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diffMinutes = ((now - epochMillis) / (1000L * 60)).toInt()
    return when {
        diffMinutes < 1 -> "just now"
        diffMinutes < 60 -> "${diffMinutes}m ago"
        diffMinutes < 1440 -> "${diffMinutes / 60}h ago"
        diffMinutes < 2880 -> "yesterday"
        diffMinutes < 10080 -> "${diffMinutes / 1440} days ago"
        else -> "${diffMinutes / 10080} weeks ago"
    }
}

private fun formatWordCount(count: Int): String = when {
    count >= 1_000_000 -> "%.1fM words".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK words".format(count / 1_000.0)
    else -> "$count words"
}

@Composable
fun WebStoriesTab(
    books: List<BookEntity>,
    isCheckingUpdates: Boolean,
    onCheckForUpdates: () -> Unit,
    onBookClick: (String) -> Unit
) {
    if (books.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No web stories yet",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Import books from a Story Manager series to track web updates",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "check_updates") {
            OutlinedButton(
                onClick = onCheckForUpdates,
                enabled = !isCheckingUpdates,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCheckingUpdates) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Checking...")
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check for Updates")
                }
            }
        }

        items(books, key = { it.bookId }) { book ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBookClick(book.bookId) }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BookCoverThumbnail(
                        coverUri = book.coverUri,
                        title = book.title,
                        width = 40.dp,
                        height = 56.dp
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
                        val serverWords = book.serverWordCount ?: 0
                        val newWords = (serverWords - book.wordCount).coerceAtLeast(0)
                        if (newWords > 0) {
                            Badge(
                                containerColor = Color(0xFF4CAF50),
                                contentColor = Color.White
                            ) {
                                Text("+${formatWordCount(newWords)}")
                            }
                        }
                        Text(
                            text = formatWordCount(book.serverWordCount ?: book.wordCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        book.contentUpdatedAt?.let { ts ->
                            Text(
                                text = "Content updated ${formatRelativeTime(ts)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
