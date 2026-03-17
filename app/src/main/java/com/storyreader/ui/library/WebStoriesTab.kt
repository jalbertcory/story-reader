package com.storyreader.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storyreader.ui.components.BookCoverThumbnail

private fun formatRelativeTime(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diffHours = ((now - epochMillis) / (1000L * 60 * 60)).toInt()
    return when {
        diffHours < 1 -> "just now"
        diffHours < 24 -> "${diffHours}h ago"
        diffHours < 48 -> "yesterday"
        diffHours < 168 -> "${diffHours / 24} days ago"
        else -> "${diffHours / 168} weeks ago"
    }
}

private fun formatWordCount(count: Int): String = when {
    count >= 1_000_000 -> "%.1fM words".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK words".format(count / 1_000.0)
    else -> "$count words"
}

@Composable
fun WebStoriesTab(
    groups: List<WebSeriesGroup>,
    onBookClick: (String) -> Unit
) {
    if (groups.isEmpty()) {
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

    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(groups, key = { it.seriesName }) { group ->
            val isExpanded = expandedGroups[group.seriesName] ?: false

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedGroups[group.seriesName] = !isExpanded }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = group.seriesName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (group.newWordsSinceLastRead > 0) {
                                    Badge {
                                        Text("+${formatWordCount(group.newWordsSinceLastRead)}")
                                    }
                                }
                            }
                            Text(
                                text = "${group.books.size} books, ${formatWordCount(group.totalServerWordCount)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            group.latestUpdate?.let { ts ->
                                Text(
                                    text = "Updated ${formatRelativeTime(ts)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        group.books.firstOrNull()?.coverUri?.let { coverUri ->
                            BookCoverThumbnail(
                                coverUri = coverUri,
                                title = group.seriesName,
                                width = 40.dp,
                                height = 56.dp
                            )
                        }
                    }

                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            group.books.forEach { book ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onBookClick(book.bookId) }
                                ) {
                                    Row(modifier = Modifier.padding(8.dp)) {
                                        BookCoverThumbnail(
                                            coverUri = book.coverUri,
                                            title = book.title,
                                            width = 36.dp,
                                            height = 50.dp
                                        )
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 8.dp)
                                        ) {
                                            Text(
                                                text = book.title,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            val serverWords = book.serverWordCount ?: 0
                                            val newWords = (serverWords - book.wordCount).coerceAtLeast(0)
                                            if (newWords > 0) {
                                                Text(
                                                    text = "+${formatWordCount(newWords)} new",
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
        }
    }
}
