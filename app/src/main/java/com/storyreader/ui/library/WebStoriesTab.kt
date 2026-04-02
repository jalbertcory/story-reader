package com.storyreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storyreader.data.db.entity.BookEntity

@Composable
fun WebStoriesTab(
    books: List<BookEntity>,
    lastReadTimes: Map<String, Long>,
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
        items(books, key = { it.bookId }) { book ->
            val serverWords = book.serverWordCount ?: 0
            val newWords = (serverWords - book.wordCount).coerceAtLeast(0)
            LibraryBookCard(
                book = book,
                lastReadAt = lastReadTimes[book.bookId],
                onClick = { onBookClick(book.bookId) },
                onLongClick = {},
                newWordsCount = newWords
            )
        }

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
    }
}
