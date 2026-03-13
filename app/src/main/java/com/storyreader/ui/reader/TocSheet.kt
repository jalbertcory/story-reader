package com.storyreader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.readium.r2.shared.publication.Link

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    toc: List<Link>,
    onNavigate: (Link) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Table of Contents",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
            HorizontalDivider()
            if (toc.isEmpty()) {
                Text(
                    text = "No table of contents available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                LazyColumn {
                    items(toc) { link ->
                        TocEntry(link = link, depth = 0, onClick = { onNavigate(link) })
                        link.children.forEach { child ->
                            TocEntry(link = child, depth = 1, onClick = { onNavigate(child) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TocEntry(link: Link, depth: Int, onClick: () -> Unit) {
    Text(
        text = link.title ?: link.href.toString(),
        style = if (depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        color = if (depth == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(
                start = (24 + depth * 16).dp,
                end = 24.dp,
                top = 12.dp,
                bottom = 12.dp
            )
    )
}
