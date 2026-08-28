package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.util.ProgressShareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Celebration dialog shown the moment the user checks off every habit for the
 * day. Renders a live preview of the exact branded card that will be shared
 * and offers one-tap sharing via the native share sheet.
 */
@Composable
fun DailyGoalShareDialog(
    data: AppData,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }

    // The 1080x1350 card renders off the main thread, then previews inline.
    LaunchedEffect(Unit) {
        preview = withContext(Dispatchers.Default) {
            ProgressShareImage.renderDailyCard(context, data).asImageBitmap()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Daily goal complete!",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Every frame checked. Here's your shareable card for today's win.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Box(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .aspectRatio(1080f / 1350f)
                        .clip(MaterialTheme.shapes.large)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = MaterialTheme.shapes.large,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = preview
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Preview of your daily goal card",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
                Button(
                    onClick = onShare,
                    enabled = preview != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.IosShare,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Share this win",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("Not now")
                }
            }
        }
    }
}
