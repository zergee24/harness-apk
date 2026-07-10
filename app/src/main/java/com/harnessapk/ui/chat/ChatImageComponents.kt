package com.harnessapk.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ChatImageDisplay {
    data class Ready(val uri: Uri, val mimeType: String) : ChatImageDisplay
    data object Loading : ChatImageDisplay
    data class Failed(val message: String) : ChatImageDisplay
}

@Composable
internal fun ChatImageThumbnail(
    image: ChatImageDisplay,
    onOpen: () -> Unit,
    onRetry: () -> Unit = {},
) {
    val thumbnailModifier = Modifier
        .widthIn(min = 64.dp, max = 260.dp)
        .fillMaxWidth()
        .aspectRatio(4f / 3f)
        .clip(RoundedCornerShape(8.dp))

    when (image) {
        is ChatImageDisplay.Ready -> {
            Surface(
                modifier = thumbnailModifier
                    .clickable(onClick = onOpen)
                    .semantics { contentDescription = "打开图片预览" },
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                ChatImageBitmap(
                    uri = image.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        ChatImageDisplay.Loading -> ImageThumbnailStatus(thumbnailModifier) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
        is ChatImageDisplay.Failed -> ImageThumbnailStatus(thumbnailModifier) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("图片加载失败", style = MaterialTheme.typography.bodyMedium)
                Text(
                    image.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("重试", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
internal fun ChatImagePreviewDialog(
    image: ChatImageDisplay,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveStatus: String?,
    onRetry: () -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.scrim,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭图片预览")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = onSave,
                        enabled = image is ChatImageDisplay.Ready,
                    ) {
                        Text("保存图片")
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (image) {
                        is ChatImageDisplay.Ready -> ChatImageBitmap(
                            uri = image.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        ChatImageDisplay.Loading -> CircularProgressIndicator()
                        is ChatImageDisplay.Failed -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("图片加载失败")
                            Text(
                                image.message,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onRetry) { Text("重试") }
                        }
                    }
                }
                saveStatus?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageThumbnailStatus(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ChatImageBitmap(
    uri: Uri,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier,
) {
    val bitmap by decodedImageBitmap(uri)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun decodedImageBitmap(uri: Uri): androidx.compose.runtime.State<Bitmap?> {
    val context = LocalContext.current
    return produceState(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri).use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
    }
}
