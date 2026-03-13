package com.storyreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

private data class TocItem(val link: Link, val depth: Int)

private fun flattenToc(toc: List<Link>): List<TocItem> = buildList {
    for (link in toc) {
        add(TocItem(link, 0))
        link.children.forEach { child -> add(TocItem(child, 1)) }
    }
}

/** Returns the index of the deepest TOC entry whose href prefix matches the current locator. */
private fun activeIndex(items: List<TocItem>, locator: Locator?): Int {
    if (locator == null) return -1
    val href = locator.href.toString()
    var bestIndex = -1
    var bestLength = -1
    items.forEachIndexed { i, item ->
        val entryHref = item.link.href.toString().substringBefore("#")
        if (href.startsWith(entryHref) && entryHref.length > bestLength) {
            bestLength = entryHref.length
            bestIndex = i
        }
    }
    return bestIndex
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    toc: List<Link>,
    currentLocator: Locator?,
    onNavigate: (Link) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val items = flattenToc(toc)
    val activeIdx = activeIndex(items, currentLocator)
    val listState = rememberLazyListState()

    // Scroll so the current chapter is visible when the sheet opens
    LaunchedEffect(activeIdx) {
        if (activeIdx >= 0) {
            listState.animateScrollToItem(activeIdx)
        }
    }

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
            if (items.isEmpty()) {
                Text(
                    text = "No table of contents available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                LazyColumn(state = listState) {
                    itemsIndexed(items) { index, item ->
                        TocEntry(
                            link = item.link,
                            depth = item.depth,
                            isActive = index == activeIdx,
                            onClick = { onNavigate(item.link) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TocEntry(link: Link, depth: Int, isActive: Boolean, onClick: () -> Unit) {
    val bgColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val textColor = when {
        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        depth == 0 -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = link.title ?: link.href.toString(),
        style = if (depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        color = textColor,
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onClick() }
            .padding(
                start = (24 + depth * 16).dp,
                end = 24.dp,
                top = 12.dp,
                bottom = 12.dp
            )
    )
}
