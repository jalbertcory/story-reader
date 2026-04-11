package com.storyreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@Composable
fun StoryReaderLinearProgressIndicator(
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier,
            drawStopIndicator = {}
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier
        )
    }
}

@Composable
fun BookProgressRow(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StoryReaderLinearProgressIndicator(
            progress = progress,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${"%.1f".format(progress * 100)}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun SelectableSettingTile(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 72.dp,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    unselectedContainerColor: Color = MaterialTheme.colorScheme.surface,
    selectedBorderColor: Color = MaterialTheme.colorScheme.primary,
    unselectedBorderColor: Color = MaterialTheme.colorScheme.outline,
    selectedBorderWidth: Dp = 2.dp,
    unselectedBorderWidth: Dp = 1.dp,
    shadowElevation: Dp = 0.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(width).border(
            width = if (selected) selectedBorderWidth else unselectedBorderWidth,
            color = if (selected) selectedBorderColor else unselectedBorderColor,
            shape = shape
        ),
        color = if (selected) selectedContainerColor else unselectedContainerColor,
        shape = shape,
        shadowElevation = shadowElevation
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
        ) {
            content()
        }
    }
}

@Composable
fun BookCoverThumbnail(
    coverUri: String?,
    title: String,
    modifier: Modifier = Modifier,
    width: Dp,
    height: Dp,
    cornerRadius: Dp = 4.dp,
    placeholderIconSize: Dp = (width * 0.65f).coerceAtLeast(24.dp),
    contentDescription: String? = "Cover of $title"
) {
    val shape = RoundedCornerShape(cornerRadius)
    if (coverUri != null) {
        AsyncImage(
            model = File(coverUri),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .width(width)
                .height(height)
                .clip(shape)
        )
    } else {
        Box(
            modifier = modifier
                .width(width)
                .height(height)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Book,
                contentDescription = null,
                modifier = Modifier.size(placeholderIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
